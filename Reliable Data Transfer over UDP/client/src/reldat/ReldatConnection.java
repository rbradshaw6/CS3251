package reldat;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;

import reldat.exception.HeaderCorruptedException;
import reldat.exception.PayloadCorruptedException;

public class ReldatConnection {
	// Maximum number of times we can retransmit a packet before we decide the server is unreachable
	public static final int MAX_RETRANSMISSION_NO = 3;
	
	// Number of seconds before we decide we haven't received anything from the server
	public static final int PACKET_TIMEOUT = 1;

	// Our maximum window size
	private int srcMaxWindowSize;
	
	// The server's maximum window size
	private int dstMaxWindowSize;
	
	// The server's IP address
	private InetAddress dstIPAddress;
	
	// The port the connection between us and the server is on
	private int port;
	
	// The UDP socket we send data to the server with
	private DatagramSocket outSocket;
	
	// The UDP socket we read data from the server with
	private DatagramSocket inSocket;
	
	// Dictionary that maps ReldatPackets to timestamps
	private HashMap<ReldatPacket, Long> timers = new HashMap<ReldatPacket, Long>();
	
	// Dictionary that maps packet sequence numbers to retransmission counts
	private HashMap<Integer, Integer> retransmissions = new HashMap<Integer, Integer>();
	
	// List of packets that have not been acknowledged by the server
	private ArrayList<ReldatPacket> unAcked = new ArrayList<ReldatPacket>();
	
	// Counter that keeps track of the sequence number of the last packet we sent
	private int currentSeq;

	// The buffer containing packets we received from the server
	private ReldatPacket[] receiveBuffer;
	
	// List of packets we have sent to the server
	private ArrayList<ReldatPacket> packetsSent = new ArrayList<ReldatPacket>();  
	
	// List of sequence numbers that we have sent to the server
	private ArrayList<Integer> seqsSent = new ArrayList<Integer>(); 
	
	// All of the data we have received from the server, as a string
	private String totalData = "";
	
	// Receive window index tracker
	private int bufferIndex = 0;
	
	// Send window index tracker
	private int sendBase = 0;
	
	/*
	 * Create a new RELDAT connection with a specific window size
	 * on our end.
	 */
	public ReldatConnection (int maxWindowSize) {
		this.srcMaxWindowSize = maxWindowSize;
		this.receiveBuffer = new ReldatPacket[maxWindowSize];
		this.currentSeq = 0;
	}

	/*
	 * Open a connection via three-way handshake. The following packet
	 * exchange occurs:
	 * 
	 * 1. Client -> Server
	 *      FLAGS:   OPEN
	 *      PAYLOAD: Client's max window size
	 * 
	 * 2. Server -> Client
	 *      FLAGS:   OPEN | ACK
	 *      PAYLOAD: Server's max window size
	 * 
	 * 3. Client -> Server
	 *      FLAGS:   ACK
	 *      PAYLOAD: Nothing
	 * 
	 * This method will return true if a connection was successfully established,
	 * or false if the server was unreachable and a connection could not be established.
	 */
	public boolean connect(String dstIPAddress, int port) throws IOException {
		try {
			this.dstIPAddress = InetAddress.getByName(dstIPAddress);
			this.port = port;
		} catch (UnknownHostException e) {
			System.out.println("Host unreachable: " + dstIPAddress);
		}
		
		System.out.println( "Attempting to connect to " + dstIPAddress + ":" + port + "..." );

		// Open up our UDP sockets for I/O, with a timeout of 1000 ms for the input socket
        try {
            this.outSocket = new DatagramSocket();	
            this.inSocket = new DatagramSocket(this.port);
            this.inSocket.setSoTimeout(ReldatConnection.PACKET_TIMEOUT * 1000);
        } catch (SocketException e) {
        	e.printStackTrace();
        }

        // Step 1. Send initial SYN to server
        ReldatPacket syn = new ReldatPacket(srcMaxWindowSize, ReldatHeader.OPEN_FLAG, this.getCurrentSequenceNumber(), 0);

        byte[] buffer = new byte[1000];
        DatagramPacket pkt  = new DatagramPacket( buffer, buffer.length );
        ReldatPacket synAck = null;
        int resends = 0;
        
        // Loop until we either hit the max resend count, or we get a SYNACK back from the server
        do {
            try {
            	// Send the UDP packet containing the RELDAT SYN packet
            	DatagramPacket packet = syn.toDatagramPacket( this.dstIPAddress, this.port );
                this.outSocket.send( packet );

                // Try to receive a SYNACK from the server
				this.inSocket.receive( pkt );
	    		synAck = ReldatPacket.bytesToPacket( pkt.getData() );
			} catch(SocketTimeoutException e) {
				System.out.println("Server did not respond - retrying...");
			} catch(HeaderCorruptedException | PayloadCorruptedException e) {
            	System.out.println("Server replied with corrupted packet - retrying...");
            	resends--; // Don't count a corrupted packet as a non-response
            }

            // Increment the number of resends, and check if we hit the max resend count
            resends++;

            // If so, get out of this loop
            if (resends >= MAX_RETRANSMISSION_NO)
            	break;
        } while (synAck == null || !(synAck.isACK() && synAck.isOpen()));

        // If we got this far and we resent it more times than the max resend count, quit trying to connect
        if (resends >= MAX_RETRANSMISSION_NO) {
        	System.out.println("Server unreachable.");
        	return false;
        }

        // SYNACK packet will contain the server's max window size, so store it
        this.dstMaxWindowSize = Integer.parseInt( new String( synAck.getData() ) );

    	// Step 3: Send ACK to server
    	ReldatPacket ack = new ReldatPacket( "", ReldatHeader.ACK_FLAG, 0, synAck.getHeader().getSequenceNumber() );
    	DatagramPacket ackPacket = ack.toDatagramPacket( this.dstIPAddress, this.port );
    	this.outSocket.send( ackPacket );

    	// Assume a connection has been established
        System.out.println( "Connection established." );
        return true;
	}
	
	/*
	 * Engage in a conversation with the data: send all the data we have to the server,
	 * and then exit when we stop receiving data from the server. This is a pipelined,
	 * two-way, simultaneous conversation meaning we will receive both ACK and data
	 * packets from the server at the same time we're sending data and ACK packets
	 * to the server.
	 */
	public String conversation(String data) {
		// Reset all our properties
		this.resetStats();

		try {
			// Packetize the data
			ReldatPacket[] pktsToSend = packetize(data);
			
			// Keep track of whether or not we sent an end-of-data packet
			boolean eodSent = false;
			
			// Keep us in the conversation loop until the server is done sending us back data
			boolean conversationOver = false;

			while (!conversationOver) {
				// If our window hasn't slid to the end of the send buffer, send a window of packets
				if (sendBase < pktsToSend.length) {
					for (int i = sendBase; i < pktsToSend.length && i < sendBase + this.dstMaxWindowSize; i++) {
						ReldatPacket pkt = pktsToSend[i];
						
						// If the packet has been un-ACKed and it isn't null, send it
						if (!unAcked.contains(pkt) && pkt != null) {
							System.out.println("Sending data.");
							this.sendData(pkt, false);
						}
					}
				}
				
				// If there are un-ACKed packets, see if any need to be re-sent
				if (unAcked.size() > 0) {
					for (ReldatPacket currPkt : unAcked) {
						// If a packet reached the timeout without being ACKed, retransmit it
						if (((new Date().getTime() - this.timers.get(currPkt)) / 1000) > ReldatConnection.PACKET_TIMEOUT) {
							if (this.retransmissions.get(currPkt.getHeader().getSequenceNumber()) < ReldatConnection.MAX_RETRANSMISSION_NO) {
								this.sendData(currPkt, true);
							} else {
								// If we re-transmitted it too many times already, assume the server is unreachable
								System.out.println("Max retransmission count reached. Assuming server failure.");
								return null;
							}
						}
					}
				} else if (!eodSent) {
					// One-shot if-block. If all packets were ACKed, then we can tell
					// the server we're done sending it data.
					try {
						System.out.println("No more data to send - sending EOD.");

						// Send an end-of-data packet
						ReldatPacket eod = new ReldatPacket("", ReldatHeader.EOD_FLAG, getCurrentSequenceNumber(), 0);
						this.sendData(eod, false);
						
						// Set eodSent so we don't enter the one-shot more than once
						eodSent = true;
					} catch (UnsupportedEncodingException e) {
						System.err.println("UTF-8 encoding is not supported on your machine.");
						System.exit(-1);
					}
				}
				
				// Listen for a packet
				conversationOver = listen();
			}
		} catch (HeaderCorruptedException | PayloadCorruptedException e) {
			e.printStackTrace();
		}
		
		// Once we get to this part, there may still be another packet
		// in the input socket if the connection was especially unstable.
		listen();

		// Flush the buffer one last time, even if it's not full
		this.flushBuffer();

		// Return all the data we received from the server
		return totalData;
	}
	
	/*
	 * Reset all of our bookkeeping stats to a "clean slate" state.
	 * Note that we don't reset the sequence number.
	 */
	private void resetStats()
	{
		this.receiveBuffer = new ReldatPacket[this.srcMaxWindowSize];
		this.timers = new HashMap<ReldatPacket, Long>();
		this.retransmissions = new HashMap<Integer, Integer>();
		this.unAcked = new ArrayList<ReldatPacket>();
		this.packetsSent = new ArrayList<ReldatPacket>();  
		this.seqsSent = new ArrayList<Integer>(); 
		this.totalData = "";
		this.bufferIndex = 0;
		this.sendBase = 0;
	}
	
	/*
	 * Listen for a packet, waiting a maximum of 1000 milliseconds before
	 * we decide there's no packet to receive.
	 * 
	 * This method returns true if we have received all data from the server,
	 * and returns false if the server still has more data to send us.
	 */
	public boolean listen()
	{
		// Prepare a buffer of the max packet size to store a potential packet in
		byte[] buffer = new byte[ReldatPacket.MAX_PACKET_SIZE];
		DatagramPacket p = new DatagramPacket(buffer, buffer.length);

		// Try to receive a packet
		try {
			this.inSocket.receive(p);
			ReldatPacket receivedPacket = ReldatPacket.bytesToPacket(p.getData());
					
			// If we got a packet, determine what to do based on its flags
			if(receivedPacket.isACK()) {
				System.out.println("Received ACK " + receivedPacket.getHeader().getAcknowledgementNumber());

				// If it's an ACK packet that is in our list of un-ACKed packets, mark it as ACKed
				if (this.unAcked.contains(receivedPacket)) {
					// If ACK received and ACK is for smallest un-ACKed packet,
					// increment the window base index to next un-ACKed sequence number
					if (receivedPacket.getHeader().getAcknowledgementNumber() == unAcked.get(0).getHeader().getSequenceNumber()) {
						this.sendBase++;
					}

					// Removed it from the un-ACKed list
					this.unAcked.remove(receivedPacket);
					
					// Remove it from the list of sequence numbers sent.
					// We want to call ArrayList.remove(Object). Since the sequence numbers sent list
					// is a list of integers, the object will be an integer, and Java will think
					// we're calling ArrayList.remove(int). So we need to cast the sequence number
					// to an object.
					this.seqsSent.remove((Object)receivedPacket.getHeader().getSequenceNumber());
				}
			} else if (receivedPacket.isData()) {
				System.out.println("Received data.");

				// If the packet is data, first check to make sure we have room for it in the buffer
				if(bufferFull())
					this.flushBuffer();
				
				if (receivedPacket.getHeader().getSequenceNumber() - this.bufferIndex >= this.srcMaxWindowSize) {
					this.bufferIndex += this.srcMaxWindowSize;
					this.bufferIndex -= receivedPacket.getHeader().getSequenceNumber() % this.srcMaxWindowSize;
				}
				
				// Get the correct index: <sequence number> - <receive window base index>,
				// which will give us an index in the range [0, <max window size>).
				int index = receivedPacket.getHeader().getSequenceNumber() - this.bufferIndex;

				// If the packet was not retransmitted, or if we haven't gotten that
				// packet yet, put it in the buffer at the correct index
				if(!receivedPacket.isRetransmit() || receiveBuffer[index] == null)
					receiveBuffer[index] = receivedPacket;
				
				// Then acknowledge the packet
				this.sendACK(receivedPacket, false);
			} else if (receivedPacket.isEOD()) {
				// If the server has no more data to send us, acknowledge the end-of-data packet
				// and return true.
				this.sendACK(receivedPacket, true);
				return true;
			} else if (receivedPacket.isNudge()) {
				// If the packet was a nudge, send back a nudge ACK to let the server know
				// we still have a connection to it.
				ReldatPacket nudge = new ReldatPacket("", (byte)(ReldatHeader.NUDGE_FLAG | ReldatHeader.ACK_FLAG), 0, 0);
				DatagramPacket nudgePkt = nudge.toDatagramPacket(this.dstIPAddress, this.port);
				this.outSocket.send(nudgePkt);
			}
		} catch (SocketTimeoutException e) {
			// Do nothing
		} catch (IOException e) {
			e.printStackTrace();
		} catch (HeaderCorruptedException | PayloadCorruptedException e) {
			// Do nothing
		}
		
		return false;
	}
	
	/*
	 * Flush the buffer, and append whatever was in the buffer
	 * to our "total received data" string.
	 */
	private void flushBuffer() {
		String bufferContents = "";

		for (ReldatPacket rp : receiveBuffer)
			if (rp != null)
				bufferContents += new String(rp.getData()); // Packet payload is a byte array
		
		this.receiveBuffer = new ReldatPacket[this.srcMaxWindowSize];
		this.totalData += bufferContents;
	}
	
	/*
	 * Send an ACK for a data packet back to the server.
	 */
	private void sendACK(ReldatPacket pkt, boolean isEOD)
	{
		System.out.println("Acknowledging received SEQ " + pkt.getHeader().getSequenceNumber() + ".");
		byte flags = ReldatHeader.ACK_FLAG;
		
		// If the packet is an EOD ACK, give it an EOD flag too
		if (isEOD)
			flags |= ReldatHeader.EOD_FLAG;

		try {
			ReldatPacket ack = new ReldatPacket("", flags, 0, pkt.getHeader().getSequenceNumber());
			DatagramPacket ackPkt = ack.toDatagramPacket(this.dstIPAddress, this.port);
			this.outSocket.send(ackPkt);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/*
	 * Returns true if the data buffer is full; false otherwise.
	 * The data buffer is full if, for every index, there is a valid
	 * packet occupying that index in the buffer.
	 */
	private boolean bufferFull()
	{
		for(int i = 0; i < receiveBuffer.length; i++) {
			if(receiveBuffer[i] == null)
				return false;
		}
		
		return true;
	}
	
	/*
	 * Send a data packet to the server.
	 */
	private void sendData(ReldatPacket pkt, boolean isRetransmission) {
		// If we're re-transmitting the packet, give it a RETRANSMIT flag
		if (isRetransmission) {
			pkt.addFlag(ReldatHeader.RETRANSMIT_FLAG);
			System.out.println("Re-sending unacknowledged data.");
		}

		DatagramPacket dgPkt = pkt.toDatagramPacket(this.dstIPAddress, this.port);
		
		try {
			this.outSocket.send(dgPkt);
		} catch (IOException e) {
			e.printStackTrace();
		}

		// Emplace a new timer for the packet in the timers dictionary
		this.timers.put(pkt, new Date().getTime());
		
		// Put this packet in the retransmissions and un-ACKed
		// list. If we're re-transmitting the packet, we don't
		// need to add it to the un-ACKed list because it's
		// already in there.
		if (!isRetransmission) {
			this.retransmissions.put(pkt.getHeader().getSequenceNumber(), 0);
			this.unAcked.add(pkt);
		}
		else
			this.retransmissions.put(pkt.getHeader().getSequenceNumber(), this.retransmissions.get(pkt.getHeader().getSequenceNumber()) + 1);
	}
	
	/*
	 * Convert a string into an array of RELDAT packets.
	 */
	private ReldatPacket[] packetize(String message) throws HeaderCorruptedException, PayloadCorruptedException {
		// Get the number of packets we'll need to create by dividing the string length by the max packet payload size
		int lastPacketNum = (int)(Math.ceil(message.length() / (float) ReldatPacket.PACKET_PAYLOAD_SIZE));

		// Create a buffer the size of the number of packets we're creating
		ReldatPacket[] pkts = new ReldatPacket[lastPacketNum];
		int currentPacketNum = 0;
				
		while (currentPacketNum < lastPacketNum) {
			// Get the next window of the message to create a packet with
			int startInd = currentPacketNum * ReldatPacket.PACKET_PAYLOAD_SIZE;
			int endInd = (currentPacketNum + 1) * ReldatPacket.PACKET_PAYLOAD_SIZE;
			String sub = (endInd > message.length()) ? message.substring(startInd) : message.substring(startInd, endInd);
		
			ReldatPacket newPkt = null;

			try {
				newPkt = new ReldatPacket(sub, ReldatHeader.DATA_FLAG, this.getCurrentSequenceNumber(), 0);
			} catch (UnsupportedEncodingException e) {
				System.err.println("UTF-8 encoding is not supported on your machine.");
				System.exit(-1);
			}

			// Add this packet to the packets we have sent to the server, because
			// although we haven't sent the packet yet, we will if we're calling this method.
			this.packetsSent.add(newPkt);
			
			// Add the packet we created to the buffer
			pkts[currentPacketNum] = newPkt;
			currentPacketNum++;
		}
		
		return pkts;
	}
	
	/*
	 * Return the current sequence number and then increment it.
	 */
	private int getCurrentSequenceNumber() {
		this.seqsSent.add(this.currentSeq);
		this.currentSeq++;
		return this.seqsSent.get(this.seqsSent.size() - 1);
	}

	/*
	 * Close the connection via four-way handshake. The following
	 * packet exchange occurs:
	 * 
	 * 1. Client -> Server
	 *      FLAGS:   CLOSE
	 *      PAYLOAD: Nothing
	 * 
	 * 2. Server -> Client
	 *      FLAGS:   CLOSE | ACK
	 *      PAYLOAD: Nothing
	 * 
	 * 3. Server -> Client
	 *      FLAGS:   CLOSE
	 *      PAYLOAD: Nothing
	 * 
	 * 4. Client -> Server
	 *      FLAGS:   CLOSE | ACK
	 *      PAYLOAD: Nothing
	 * 
	 * This method returns true if disconnect was successful, and
	 * false if the server did not respond and the disconnect was unsuccessful.
	 */
	public boolean disconnect()
	{
		System.out.println( "Attempting to disconnect from " + this.dstIPAddress + ":" + this.port + "..." );
		
        byte[] buffer = new byte[1000];
        DatagramPacket closeAck1Pkt = new DatagramPacket( buffer, buffer.length );
		ReldatPacket closeAck1 = null;
		int resends = 0;

		// Step 1. Send client-side CLOSE to server. Keep
		// looping until we either hit the max retransmission count
		// or we get a CLOSEACK packet back from the server.
		do {
			try {
				ReldatPacket clientClose = new ReldatPacket("", ReldatHeader.CLOSE_FLAG, this.getCurrentSequenceNumber(), 0);
				DatagramPacket clientClosePacket = clientClose.toDatagramPacket(this.dstIPAddress, this.port);
				this.outSocket.send(clientClosePacket);
								
				// Try to receive server-side CLOSEACK from server
	            this.inSocket.receive(closeAck1Pkt);
	            closeAck1 = ReldatPacket.bytesToPacket(closeAck1Pkt.getData());
			} catch (SocketTimeoutException e) {
				System.out.println("Server did not respond - retrying...");
			} catch (HeaderCorruptedException | PayloadCorruptedException e) {
            	System.out.println("Server replied with corrupted packet - retrying...");
            	resends--; // Don't count a corrupted packet as a non-response
            } catch (UnsupportedEncodingException e) {
				System.err.println("UTF-8 encoding is not supported on your machine.");
				System.exit(-1);
			} catch (IOException e) {
				e.printStackTrace();
			}

			// Increment the send count, and if exceeds the max retransmission count, stop trying to disconnect
            resends++;

            if (resends >= MAX_RETRANSMISSION_NO)
            	break;
		} while (closeAck1 == null || !(closeAck1.isClose() && closeAck1.isACK()));
		
		// If we get here and the max resend count was hit, assume the server is unreachable
        if (resends >= MAX_RETRANSMISSION_NO) {
        	System.out.println("Server did not respond. Assuming server failure.");
        	return false;
        }

        // We got a CLOSE ACK from the server, so now we're expecting a CLOSE packet
		buffer = new byte[1000];
		DatagramPacket serverClosePkt = new DatagramPacket(buffer, buffer.length);
		ReldatPacket serverClose = null;
		resends = 0;

		// Try three times to receive a CLOSE from the server
		do {
			try {
				// Try to receive server-side CLOSE from server
				this.inSocket.receive(serverClosePkt);
				serverClose = ReldatPacket.bytesToPacket(serverClosePkt.getData());
			} catch(SocketTimeoutException e) {
				// Do nothing if we time out
			} catch(HeaderCorruptedException | PayloadCorruptedException e) {
				System.out.println("Server replied with corrupted packet - retrying..");
				resends--;
			} catch (IOException e) {
				e.printStackTrace();
			}
			
			// Increase the wait counter, and if we hit the max retransmission time, assume the server is unreachable
			resends++;
			
			if (resends >= MAX_RETRANSMISSION_NO)
				break;
		} while (serverClose == null || !serverClose.isClose());
		
		// If we get here and the max resend count was hit, assume the server is unreachable
        if (resends >= MAX_RETRANSMISSION_NO) {
        	System.out.println("Server did not respond. Assuming server failure.");
        	return false;
        }
				
		// Step 4. Send client-side ACK to server
		try {
			ReldatPacket closeAck2 = new ReldatPacket("", (byte)(ReldatHeader.CLOSE_FLAG | ReldatHeader.ACK_FLAG), 0, serverClose.getHeader().getSequenceNumber());
			DatagramPacket closeAck2Packet = closeAck2.toDatagramPacket(this.dstIPAddress, this.port);
			this.outSocket.send(closeAck2Packet);
		} catch (UnsupportedEncodingException e) {
			System.err.println("UTF-8 encoding is not supported on your system.");
			System.exit(-1);
		} catch (IOException e) {
			e.printStackTrace();
		}
        
		// Close our sockets
		this.outSocket.close();
		this.inSocket.close();

        System.out.println("Connection terminated.");
        return true;
	}
}
