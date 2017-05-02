package reldat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/*
 * RELDAT packet structure is as follows:
 * 
 * 0[N][E][D][R][A][C][O]         1 byte  (Flags)
 * [Sequence Number]              4 bytes
 * [ACK Number]                   4 bytes
 * [Payload Size]                 4 bytes
 * [Payload Checksum]            16 bytes
 * [Header Checksum]             16 bytes
 * -----------------------------
 * [ P   A   Y   L   O   A   D ] <= 955 bytes
 * 
 * N = Packet is used to ensure the connection has not been unexpectedly terminated
 * E = Packet is EOD notification
 * D = Packet contains data
 * R = Retransmission bit (1 if data in payload has already been transmitted
 *     before; false otherwise)
 * A = ACK bit (1 if packet is an acknowledgement; false otherwise)
 * C = Request for connection close bit (only 1 during connection open process)
 * O = Request for connection open bit (only 1 during connection close process)
 * 
 * If the sequence number is 0, the ACK number is non-zero, and
 * vice-versa.
 */

public class ReldatHeader
{
	// See the comment above for how the header size was calculated
	public static final short PACKET_HEADER_SIZE = 1 + 4 + 4 + 4 + 16 + 16;
	
	// Available flags a packet can be created with
	public static final byte OPEN_FLAG  	 = 0b00000001;
	public static final byte CLOSE_FLAG 	 = 0b00000010;
	public static final byte ACK_FLAG   	 = 0b00000100;
	public static final byte RETRANSMIT_FLAG = 0b00001000;
	public static final byte DATA_FLAG       = 0b00010000;
	public static final byte EOD_FLAG        = 0b00100000;
	public static final byte NUDGE_FLAG      = 0b01000000;
	
	private byte flags;
	private int seqNum;
	private int ackNum;
	private int payloadSize;
	private byte[] payloadChecksum;

	/*
	 * Construct a RELDAT header using the given flags, sequence number,
	 * acknowledgement number, and packet payload (to generate a payload
	 * checksum with).
	 */
	public ReldatHeader(byte flags, int seqNum, int ackNum, byte[] data) {
		this.flags = flags;
		this.seqNum = seqNum;
		this.ackNum = ackNum;
		this.payloadSize = data.length;
		
		// Generate the checksum for the packet payload
		try {
			MessageDigest checksumGenerator = MessageDigest.getInstance( "MD5" );
			this.payloadChecksum = checksumGenerator.digest( data );
		}
		catch (NoSuchAlgorithmException e) {
			System.out.println("An MD5 algorithm implementation does not exist on your machine.");
			System.exit(-1);
		}
	}
	
	/*
	 * Private constructor for bytesToHeader().
	 */
	private ReldatHeader( byte[] payloadChecksum, byte flags, int seqNum, int ackNum, int payloadSize )
	{
		this.flags = flags;
		this.seqNum = seqNum;
		this.ackNum = ackNum;
		this.payloadSize = payloadSize;
		this.payloadChecksum = payloadChecksum;
	}
	
	public byte getFlags() {
		return flags;
	}
	
	public int getSequenceNumber() {
		return seqNum;
	}
	
	public int getAcknowledgementNumber() {
		return ackNum;
	}
	
	public byte[] getPayloadChecksum() {
		return payloadChecksum;
	}
	
	public int getPayloadSize() {
		return payloadSize;
	}
	
	/*
	 * Get a byte array representation of this packet header.
	 */
	public byte[] toBytes() {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream output = new DataOutputStream( bytes );      
		
		try {
			output.writeByte(flags);
			output.writeInt(seqNum);
			output.writeInt(ackNum);
			output.writeInt(payloadSize);
			output.write(payloadChecksum);
			output.close();
		}
		catch (IOException e) {
			e.printStackTrace();
		}

		return bytes.toByteArray();
	}
	
	public void addFlag(byte flag) {
		this.flags |= flag;
	}
	
	/*
	 * Parse a byte array as a RELDAT packet header and convert
	 * it into a ReldatHeader object. For details on how this
	 * method works, refer to the packet structure in the
	 * comment at the top of this file.
	 */
	public static ReldatHeader bytesToHeader (byte[] header) {
		// Flags are always the first byte in the array
		byte flags = header[0];
		
		// Sequence number is a 32-bit integer, so the next four bytes are the sequence number
		int seqNum = ((0xFF & header[1]) << 24) | ((0xFF & header[2]) << 16) | ((0xFF & header[3]) << 8) | (0xFF & header[4]);
		
		// ACK number is also a 32-bit integer, so the next four bytes are the sequence number
		int ackNum = ((0xFF & header[5]) << 24) | ((0xFF & header[6]) << 16) | ((0xFF & header[7]) << 8) | (0xFF & header[8]);
		
		// Payload size is also a 32-bit integer, so the next four bytes are the payload size
		int payloadSize = ((0xFF & header[9]) << 24) | ((0xFF & header[10]) << 16) | ((0xFF & header[11]) << 8) | (0xFF & header[12]);
		
		// Last 16 bytes are the payload checksum
		byte[] payloadChecksum = new byte[16];
		System.arraycopy( header, 13, payloadChecksum, 0, 16 );

		return new ReldatHeader( payloadChecksum, flags, seqNum, ackNum, payloadSize );
	}

	/*
	 * Auto-generated hashCode() method generated by Eclipse.
	 */
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ackNum;
		result = prime * result + flags;
		result = prime * result + Arrays.hashCode(payloadChecksum);
		result = prime * result + payloadSize;
		result = prime * result + seqNum;
		return result;
	}

	/*
	 * Auto-generated equals() method generated by Eclipse, then
	 * modified heavily. Since the only time equals() is called
	 * is when we're comparing ACK packets to the data packets
	 * they're ACKing, we compare the sequence number to the
	 * acknowledgement number.
	 */
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;

		if (obj == null)
			return false;

		if (getClass() != obj.getClass())
			return false;

		ReldatHeader other = (ReldatHeader) obj;

		// If the sequence number is 0, then this is an ACK
		// packet - otherwise, it's a data packet
		if(this.seqNum == 0)
			return this.ackNum == other.seqNum;
		else
			return this.seqNum == other.ackNum;
	}
}
