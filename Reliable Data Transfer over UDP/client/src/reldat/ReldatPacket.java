package reldat;

import java.io.UnsupportedEncodingException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import reldat.exception.HeaderCorruptedException;
import reldat.exception.PayloadCorruptedException;

public class ReldatPacket {
	// Max packet size, in bytes
	public static final short MAX_PACKET_SIZE = 1000;
	
	// Max packet payload size, in bytes
	public static final short PACKET_PAYLOAD_SIZE = MAX_PACKET_SIZE - ReldatHeader.PACKET_HEADER_SIZE;

	private ReldatHeader header;
	
	// The header checksum is not stored in the header, but it is considered part of the header
	private byte[] headerChecksum;
	
	// The packet's payload
	private byte[] data;

	/*
	 * Construct a RELDAT packet with a single integer as the payload.
	 */
	public ReldatPacket(int data, byte flags, int seqNum, int ackNum) throws UnsupportedEncodingException {
		this(Integer.toString(data), flags, seqNum, ackNum);
	}

	/*
	 * Construct a RELDAT packet with a single string as the payload.
	 */
	public ReldatPacket(String data, byte flags, int seqNum, int ackNum) throws UnsupportedEncodingException {
		this(data.getBytes("UTF-8"), flags, seqNum, ackNum);
	}

	/*
	 * Construct a RELDAT packet with a byte array as the payload.
	 */
	public ReldatPacket(byte[] data, byte flags, int seqNum, int ackNum) {
		this.data = data;
		
		// If we pass in null, the payload contains no data
		if(this.data == null)
			this.data = new byte[]{};

		this.header = new ReldatHeader(flags, seqNum, ackNum, data);

		// Generate the header checksum
		try {
			MessageDigest checksumGenerator = MessageDigest.getInstance("MD5");
			this.headerChecksum = checksumGenerator.digest(header.toBytes());
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		}
	}

	/*
	 * Private constructor for bytesToPacket().
	 */
	private ReldatPacket( ReldatHeader header, byte[] headerChecksum, byte[] data ) {
		this.header = header;
		this.headerChecksum = headerChecksum;
		this.data = data;
	}

	public ReldatHeader getHeader() {
		return this.header;
	}

	public byte[] getHeaderChecksum() {
		return this.headerChecksum;
	}

	public byte[] getData() {
		return this.data;
	}
	
	/*
	 * Get the packet's payload as a UTF-8 encoded string, regardless
	 * of the constructor used to instantiate this packet.
	 */
	public String getPayload() {
		try {
			return new String(this.data, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			System.err.println("UTF-8 is not supported on your machine.");
			System.exit(-1);
		}
		
		return null;
	}
	
	public boolean isACK() {
		return (this.getHeader().getFlags() & ReldatHeader.ACK_FLAG) == ReldatHeader.ACK_FLAG;
	}
	
	public boolean isRetransmit() {
		return (this.getHeader().getFlags() & ReldatHeader.RETRANSMIT_FLAG) == ReldatHeader.RETRANSMIT_FLAG;
	}
	
	public boolean isEOD() {
		return (this.getHeader().getFlags() & ReldatHeader.EOD_FLAG) == ReldatHeader.EOD_FLAG;
	}
	
	public boolean isData() {
		return (this.getHeader().getFlags() & ReldatHeader.DATA_FLAG) == ReldatHeader.DATA_FLAG;
	}
	
	public boolean isOpen() {
		return (this.getHeader().getFlags() & ReldatHeader.OPEN_FLAG) == ReldatHeader.OPEN_FLAG;
	}
	
	public boolean isNudge() {
		return (this.getHeader().getFlags() & ReldatHeader.NUDGE_FLAG) == ReldatHeader.NUDGE_FLAG;
	}
	
	public boolean isClose() {
		return (this.getHeader().getFlags() & ReldatHeader.CLOSE_FLAG) == ReldatHeader.CLOSE_FLAG;
	}

	/*
	 * Get a byte array representation of this packet.
	 */
	public byte[] toBytes() {
		// Get the header as a byte array
		byte[] headerBytes = this.header.toBytes();
		
		// Create a byte buffer the size of this packet
		byte[] packetBytes = new byte[headerBytes.length + this.headerChecksum.length + this.data.length];

		// Copy the header into the buffer
		System.arraycopy(
			headerBytes,
			0,
			packetBytes,
			0,
			headerBytes.length
		);

		// Copy the header checksum into the buffer (this is considered part of the header)
		System.arraycopy(
			this.headerChecksum,
			0,
			packetBytes,
			headerBytes.length,
			this.headerChecksum.length
		);

		// Copy the payload into this buffer
		System.arraycopy(
			this.data,
			0,
			packetBytes,
			headerBytes.length + this.headerChecksum.length,
			this.data.length
		);

		return packetBytes;
	}

	/*
	 * Get a UDP datagram representation of this packet.
	 */
	public DatagramPacket toDatagramPacket(InetAddress dstIPAddress, int port) {
		byte[] thisBytes = this.toBytes();
		return new DatagramPacket(thisBytes, thisBytes.length, dstIPAddress, port);
	}
	
	/*
	 * Add a flag to this packet. Typically only used to add
	 * a RETRANSMIT bit when re-transmitting the same packet.
	 */
	public void addFlag(byte flag) {
		this.header.addFlag(flag);

		// Recalculate the checksum right here because we're extremely efficient
		try {
			MessageDigest checksumGenerator = MessageDigest.getInstance( "MD5" );
			headerChecksum = checksumGenerator.digest( header.toBytes() );
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		}
	}

	/*
	 * Parse a byte array as a RELDAT packet and convert
	 * it into a ReldatPacket object.
	 */
	public static ReldatPacket bytesToPacket(byte[] packetData) throws HeaderCorruptedException, PayloadCorruptedException {
		// Create a buffer for the header
		byte[] headerBytes = new byte[ReldatHeader.PACKET_HEADER_SIZE - 16];
		
		// Create a buffer for the header checksum
		byte[] headerChecksum = new byte[16];

		// Copy data into the header buffer
		System.arraycopy(
			packetData,
			0,
			headerBytes,
			0,
			ReldatHeader.PACKET_HEADER_SIZE - 16
		);

		// Copy data into the header checksum buffer
		System.arraycopy(
			packetData,
			ReldatHeader.PACKET_HEADER_SIZE - 16,
			headerChecksum,
			0,
			16
		);

		try {
			// Generate a checksum from the header buffer
			MessageDigest checksumGenerator = MessageDigest.getInstance( "MD5" );

			byte[] expectedHeaderChecksum = checksumGenerator.digest( headerBytes );
			checksumGenerator.reset();

			// Compare the checksum we just generated to the header checksum buffer
			if( !Arrays.equals( headerChecksum, expectedHeaderChecksum ) )
				throw new HeaderCorruptedException(); // If they're not the same, the header is corrupted

			// Convert the header buffer into a ReldatHeader object
			ReldatHeader header = ReldatHeader.bytesToHeader( headerBytes );
			
			// Create a payload buffer using the payload size specified in the header
			int payloadSize = header.getPayloadSize();
			byte[] payloadBytes = new byte[payloadSize];

			// Copy data into the payload buffer
			System.arraycopy(
				packetData,
				ReldatHeader.PACKET_HEADER_SIZE,
				payloadBytes,
				0,
				payloadSize
			);

			// Generate an MD5 checksum from the payload buffer
			byte[] expectedPayloadChecksum = checksumGenerator.digest( payloadBytes );
			checksumGenerator.reset();

			// Compare the checksum we just generated to the payload checksum in the header
			if( !Arrays.equals( header.getPayloadChecksum(), expectedPayloadChecksum ) )
				throw new PayloadCorruptedException(); // If they're not the same, the payload is corrupted

			// Finally return an object representation of the byte array
			return new ReldatPacket( header, headerChecksum, payloadBytes );
		}
		catch (NoSuchAlgorithmException e) {
			System.out.println("An MD5 algorithm implementation does not exist on your machine.");
			System.exit(-1);
		}

		return null;
	}

	/*
	 * Auto-generated hashCode() method generated by Eclipse.
	 */
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + Arrays.hashCode(data);
		result = prime * result + ((header == null) ? 0 : header.hashCode());
		result = prime * result + Arrays.hashCode(headerChecksum);
		return result;
	}

	/*
	 * Auto-generated equals() method generated by Eclipse, then simplified
	 * to only compare headers. If the headers are equal (see ReldatPacket.equals()
	 * for what "header equality" entails), then the packets are equal.
	 */
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;

		if (obj == null)
			return false;

		if (getClass() != obj.getClass())
			return false;

		ReldatPacket other = (ReldatPacket) obj;
		return other.getHeader().equals(this.getHeader());
	}
}
