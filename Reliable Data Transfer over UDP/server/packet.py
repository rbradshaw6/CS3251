import hashlib
import struct
import math

'''
The next four functions construct and deconstruct packets.
Packet structure is as follows:

0[N][E][D][R][A][C][O]         1 byte
[Sequence Number]              4 bytes
[ACK Number]                   4 bytes
[Payload Size]                 4 bytes
[Payload Checksum]            16 bytes
[Header Checksum]             16 bytes
-----------------------------
[ P   A   Y   L   O   A   D ] <= 955 bytes

N = Packet is used to ensure the connection has not been unexpectedly terminated
E = Packet is end-of-data
D = Packet contains data
R = Retransmission bit (1 if data in payload has already been transmitted
    before; false otherwise)
A = ACK bit (1 if packet is an acknowledgement; false otherwise)
C = Request for connection close bit (only 1 during connection open process)
O = Request for connection open bit (only 1 during connection close process)
'''

# Maximum value of an unsigned long long
_ULL = 0xFFFFFFFFFFFFFFFF

# Max packet size, in bytes
MAX_PACKET_SIZE = 1000

# Max packet header size, in bytes
PACKET_HEADER_SIZE = 1 + 4 + 4 + 4 + 16 + 16

# Max packet payload size, in bytes
PACKET_PAYLOAD_SIZE = MAX_PACKET_SIZE - PACKET_HEADER_SIZE

def construct_header( data, seq_num, ack_num, flags=[]):
    '''
    constructs the header for a packet ready to be sent over the wire
    '''
    header_flags = 0

    for flag in flags:
        header_flags |= flag

    hash_calc = hashlib.md5()
    hash_calc.update( data )
    checksum = int( hash_calc.hexdigest(), 16 )

    checksum_parts = (
        ( checksum >> 64 ) & _ULL,
        checksum & _ULL
    )

    return struct.pack(
        '!'   # use network order
        'B'   # flags
        'I'   # seq num
        'I'   # ack num
        'I'   # payload size
        '2Q', # checksum
        header_flags, seq_num, ack_num, len( data ), checksum_parts[0], checksum_parts[1]
    )

def construct_packet( data, seq_num, ack_num, flags=[] ):
    '''
    constructs a packet that is ready to send over the wire
    :param data: str
    :param seq_num: int
    :param ack_num: int
    :param flags: [int]
    :return: packet
    '''
    
    # Construct the header
    header = construct_header( data, seq_num, ack_num, flags )

    # Calculate the header checksum, but do not store it in the header
    hash_calc = hashlib.md5()
    hash_calc.update( header )

    # Since the maximum integer size in Python is 64 bits,
    # we need to turn the 128-bit header checksum into a tuple
    # containing the first 64 bits and the second 64 bits.
    header_checksum = int( hash_calc.hexdigest(), 16 )
    header_checksum_parts = (
        ( header_checksum >> 64 ) & _ULL,
        header_checksum & _ULL
    )

    # Pack the packet header, effectively turning it into a byte array
    header_checksum_struct = struct.pack(
        '!'   # use network order
        '2Q', # checksum
        header_checksum_parts[0], header_checksum_parts[1]
    )

    return header + header_checksum_struct + data

def deconstruct_header( packet_header ):
    '''
    returns a tuple containing the headers information: flags, seq_num, ack_num, payload_size, checksum
    :param packet_header: byte-array
    :return: tuple
    '''
    
    # Unpack the packet header as if it were a byte array - refer
    # to construct_header for the significance of '!BIII2Q'
    ( flags, seq_num, ack_num, payload_size, checksum_1, checksum_2 ) = struct.unpack(
        '!BIII2Q',
        packet_header
    )

    checksum = ( ( checksum_1 & _ULL ) << 64 ) | ( checksum_2 & _ULL )
    return ( flags, seq_num, ack_num, payload_size, checksum )

def deconstruct_packet( packet_data ):
    '''
    given a packet recieved from the client, this will create a packet tuple containing the different parts. Flags, seq_num,
    ack_num, packet_payload
    :param packet_data: byte_array
    :return: tuple
    '''
    
    # Get the header header checksum parts from the packet data
    packet_header   = packet_data[:PACKET_HEADER_SIZE - 16]
    header_checksum = packet_data[PACKET_HEADER_SIZE - 16 : PACKET_HEADER_SIZE]

    # Unpack the packet header as if it were a byte array - refer
    # to construct_packet for the significance of '!2Q'
    ( header_checksum_1, header_checksum_2 ) = struct.unpack(
        '!2Q',
        header_checksum
    )

    header_checksum = ( ( header_checksum_1 & _ULL ) << 64 ) | ( header_checksum_2 & _ULL )

    # Make sure the header checksums are the same
    hash_calc = hashlib.md5()
    hash_calc.update( packet_header )
    expected_header_checksum = hash_calc.hexdigest()

    if header_checksum != int( expected_header_checksum, 16 ):
        raise HeaderCorruptedError()

    # Get the header fields from the packet header bytes
    ( flags, seq_num, ack_num, payload_size, checksum ) = deconstruct_header( packet_header )
    
    # Get the payload part from the packet data
    packet_payload = packet_data[PACKET_HEADER_SIZE : PACKET_HEADER_SIZE + payload_size]

    # Make sure the packet data was not corrupted
    hash_calc = hashlib.md5()
    hash_calc.update( packet_payload )
    expected_checksum = hash_calc.hexdigest()

    if checksum != int( expected_checksum, 16 ):
        raise PayloadCorruptedError()

    # Return a tuple containing packet data
    return ( flags, seq_num, ack_num, packet_payload )

# Header flags
OPEN_FLAG       = 0b00000001
CLOSE_FLAG      = 0b00000010
ACK_FLAG        = 0b00000100
RETRANSMIT_FLAG = 0b00001000
DATA_FLAG       = 0b00010000
EOD_FLAG        = 0b00100000
NUDGE_FLAG      = 0b01000000
RESERVE_FLAG_4  = 0b10000000


class Packet:
    '''
    Wrapper around a packet
    '''
    def __init__(self, data):
        packet_tuple    = deconstruct_packet(data)
        self.seq_num    = packet_tuple[1]
        self.ack_num    = packet_tuple[2]
        self.payload    = packet_tuple[3]
        self.flag       = packet_tuple[0]

    def is_open(self):
        '''
        indicates if the open flag was set
        :return: boolean
        '''
        return self.flag & OPEN_FLAG

    def is_close(self):
        '''
        indicates if the close flag was set
        :return: boolean
        '''
        return self.flag & CLOSE_FLAG

    def is_ack(self):
        '''
        indicates if the ack flag was set
        :return: boolean
        '''
        return self.flag & ACK_FLAG

    def is_retransmit(self):
        '''
        indicates if the retransmit flag was set
        :return: boolean
        '''
        return self.flag & RETRANSMIT_FLAG

    def is_data(self):
        '''
        indicates if the data flag was set
        :return: boolean
        '''
        return self.flag & DATA_FLAG

    def is_eod(self):
        '''
        indicates if the eod flag was set
        :return: boolean
        '''
        return self.flag & EOD_FLAG

    def get_raw(self):
        '''
        returns the tuple that can be used to send a packet.
        :return: tuple
        '''
        return (self.flag, self.seq_num, self.ack_num, self.payload)

    def is_nudge(self):
        '''
        indicates if the nudge flag was set
        :return: boolean
        '''
        return self.flag & NUDGE_FLAG

    def add_flag(self, flag):
        '''
        sets the passed in flag on this packet.
        :param flag: int
        :return: None
        '''
        self.flag |= flag

def SYNACK( window_size, syn_seq_num ):
    '''
    returns a ready-to-send synack packet
    :param window_size: int
    :param syn_seq_num: int
    :return: tuple
    '''
    return construct_packet( window_size, 0, syn_seq_num, [ OPEN_FLAG, ACK_FLAG ] )

def ACK(seq_num):
    '''
    returns a ready-to-send ack packet
    :param window_size: int
    :param syn_seq_num: int
    :return: tuple
    '''
    return construct_packet('', 0, seq_num, [ACK_FLAG])

def EODACK(eod_seq_num):
    '''
    returns a ready-to-send packet
    :param window_size: int
    :param syn_seq_num: int
    :return: tuple
    '''
    return construct_packet('', 0, eod_seq_num, [ACK_FLAG, EOD_FLAG])

def CLOSEACK(ack_num):
    '''
    returns a ready-to-send closeack packet
    :param window_size: int
    :param syn_seq_num: int
    :return: tuple
    '''
    return construct_packet( '', 0, ack_num, [ CLOSE_FLAG, ACK_FLAG ] )

def CLOSE(seq_num):
    '''
    returns a ready-to-send close packet
    :param window_size: int
    :param syn_seq_num: int
    :return: tuple
    '''
    return construct_packet( '', seq_num, 0, [ CLOSE_FLAG ] )

class PacketIterator:
    '''
    The following class defines a black box that accepts data and packetizes
    it. Every iteration returns the next packet that composes the data.
    '''
    def __init__( self, data, window_size, seq_num_func):
        self.data        = data
        self.window_size = window_size

        self.last_packet_num = int( math.ceil( len( data ) / float( PACKET_PAYLOAD_SIZE ) ) )
        self.curr_packet_num = 0

        self.seq_num_func = seq_num_func

    def __iter__( self ):
        return self

    def next( self ):
        if self.curr_packet_num >= self.last_packet_num:
            raise StopIteration
        else:
            send_data_start = self.curr_packet_num * PACKET_PAYLOAD_SIZE
            send_data_end   = ( self.curr_packet_num + 1 ) * PACKET_PAYLOAD_SIZE

            if send_data_end > len( self.data ):
                send_data = self.data[send_data_start:]
            else:
                send_data = self.data[send_data_start : send_data_end]

            self.curr_packet_num += 1
            packet                = construct_packet( send_data, self.seq_num_func(), 0, [DATA_FLAG] )

            return packet



'''
The following classes define exceptions that may be raised during transmission.
These should be handled appropriately so as not to prematurely kill the client
or the server.
'''

class HeaderCorruptedError( Exception ):
    def __init__( self ):
        return

class PayloadCorruptedError( Exception ):
    def __init__( self ):
        return
