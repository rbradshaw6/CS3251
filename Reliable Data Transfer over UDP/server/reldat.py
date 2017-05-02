import datetime
import socket
from time import sleep

from packet import PacketIterator, Packet, ACK, SYNACK, CLOSEACK, CLOSE, EODACK, DATA_FLAG, EOD_FLAG, RETRANSMIT_FLAG, construct_packet, NUDGE_FLAG


class Reldat( object ):
    '''
    This class is the reldat server. Follows the protocol outlined in the readme
    '''
    def __init__( self, max_window_size ):
        self.src_ip_address      = socket.gethostbyname( socket.gethostname() )
        self.src_max_window_size = max_window_size

        self.dst_ip_address      = None
        self.dst_max_window_size = None
        self.on_handshake        = 0
        self.on_teardown         = 0

        self.port       = None
        self.in_socket  = None
        self.out_socket = None
        self.timeout = 3 #seconds
        self.max_retransmissions = 3
        # Need to ACK
        self.seqs_recd = []

        # Waiting for ACK
        self.seqs_sent = []
        self.timers    = {}

        self.pkt_buffer = [None for _ in range(self.src_max_window_size)]

        self.on_seq = 0
        self.eod_recd = False

        self.last_recieved = None

    def send_ack(self, packet, eod=False):
        '''
        Send an ack for the passed in packet. Ack will contain the correct sequence num.
        :param packet: Packet
        :param eod: boolean - indicates whether the packet being acked had the eod flag set
        :return: None
        '''

        if eod:
            print 'Acknowledging EOD.'
            ack_pkt = EODACK(packet.seq_num)
        else:
            print 'Acknowledging received SEQ ' + str(packet.seq_num) + '.'
            ack_pkt = ACK(packet.seq_num)

        self.out_socket.sendto(ack_pkt, self.dst_ip_address)

    def get_seq_num(self):
        '''
        increments and returns the correct sequence number to be used for the the next packet to be sent. Should only
        be called once per sending a packet.
        :return: int
        '''
        self.seqs_sent.append(self.on_seq)
        self.on_seq += 1
        return self.seqs_sent[-1]

    def open_socket(self, port):
        '''
        Opens the in and out sockets for the client at the indicated port.
        :param port: int
        :return: None
        '''
        self.port       = port
        self.in_socket  = socket.socket( socket.AF_INET, socket.SOCK_DGRAM )
        self.out_socket = socket.socket( socket.AF_INET, socket.SOCK_DGRAM )
        
        self.in_socket.settimeout(1)

        self.in_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.in_socket.bind( ( self.src_ip_address, self.port ) )

        print 'Listening on port ' + str( self.port ) + '.'

    ind_start = 0
    all_data  = ""

    def listen( self ):
        try:
            data, address = self.in_socket.recvfrom( 1024 )
            packet        = Packet( data )

            self.last_recieved = datetime.datetime.now()

            if not self.has_connection():
                self.establish_connection( address, packet )
            else:
                if packet.is_close():
                    self.disconnect(packet)
                    return
                elif packet.is_data():
                    self.handle_data(packet)
                elif packet.is_ack():
                    self.handle_ack(packet)
                elif packet.is_eod():
                    self.handle_eod(packet)
        except socket.timeout:
            pass
        except socket.error:
            pass

    def handle_data(self, packet):
        '''
            should be called when the packet.is_data() flag is set. Handles the data in the packet and sends back an ack
            :param packet: Packet
            :return:  None
        '''
        print 'Received data.'

        self.seqs_recd.append(packet.seq_num)

        if self.is_buffer_full() and not packet.is_retransmit():
            data = self.flush_buffer()
            Reldat.all_data += data
            self.send(data)
        
        if packet.seq_num - Reldat.ind_start >= self.src_max_window_size:
            Reldat.ind_start += self.src_max_window_size
            Reldat.ind_start -= packet.seq_num % self.src_max_window_size

        index = packet.seq_num - Reldat.ind_start

        if not packet.is_retransmit() or packet.seq_num not in self.seqs_recd:
            self.pkt_buffer[index] = packet

        self.send_ack(packet)

    def handle_ack(self, packet):
        '''
        should be called if a packet's is_ack() flag is set. Stops the timer for the ack'd oacket
        :param packet:
        :return:
        '''
        print 'Received acknowledgement.'

        try:
            if packet.is_nudge():
                del self.timers['NUDGE']
            else:
                del self.timers[str(packet.ack_num)]
        except KeyError:
            # If we get here, it means the server sent us an ACK
            # for the same packet twice, possibly due to network
            # delays. These can be ignored.
            pass

        if not self.timers and self.is_buffer_empty() and self.eod_recd:
            eod = construct_packet('', self.get_seq_num(), 0, [EOD_FLAG])
            self._send_raw_packet(eod)
            self.eod_recd = False

    def handle_eod(self, packet):
        '''
        should be called if a packets is_eod() flag is set. sends the appropriate ack, flushes the buffer and sends back
        the approriate response.
        :param packet:
        :return:
        '''
        print 'Received all data from client.'

        self.eod_recd = True
        self.send_ack(packet, True)

        data = self.flush_buffer()
        Reldat.all_data += data
        self.send(data)


        Reldat.ind_start = 0
        Reldat.all_data = ""

    def establish_connection( self, dst_ip_address, packet ):
        '''
        Handles every step of the establishing a connection. Should be called as long as has_connection() returns false
        which for any connection setup will be twice: once for the Syn in which case this method will send back a SynAck,
         and once for the last ack of establishing a connection. If this isn't called for those two times,
         has_connection() will return false. Takes care of window size/and sequence number considerations.
        :param dst_ip_address: clients ip address
        :param packet: packet sent from client.
        :return: None
        '''
        if self.on_handshake is 0:
            if packet.is_open():
                print 'Attempting to establish connection with ' + str( dst_ip_address[0] ) + ':' + str( self.port ) + '...'

                self.dst_ip_address      = ( dst_ip_address[0], self.port )
                self.dst_max_window_size = int( packet.payload )

                synack = SYNACK(str(self.src_max_window_size), packet.seq_num)
                self._send_raw_packet(synack)
                self.on_handshake = 1
        elif self.on_handshake is 1:
            if not packet.is_open():
                if packet.is_ack():
                    del self.timers[str(packet.ack_num)]
                    print 'Connection established.'
                    self.on_handshake = 2
                    Reldat.ind_start = 1

    def check_connection(self):
        '''
        Sends a "NUDGE" packet to the client and sets a timer for it. The rest is handled by the default processes of
        this protocol - if the client does not respond after 3 repeated nudges, the connection will be closed with the
        assumption the client has crashed.
        :return: None
        '''
        if self.has_connection() and datetime.datetime.now() - self.last_recieved > datetime.timedelta(seconds=self.timeout) and len(self.timers.keys()) is 0:
            print 'Nudging client.'
            self._send_raw_packet(construct_packet("", 0, 0, [NUDGE_FLAG]))

    def is_buffer_empty(self):
        '''
        indicates whether or not the buffer is empty.
        :return: boolean
        '''
        for data in self.pkt_buffer:
            if (data is not None):
                return False
        
        return True

    def is_buffer_full(self):
        '''
        indicates whether or not the buffer is full
        :return: boolean
        '''
        for data in self.pkt_buffer:
            if (data is None):
                return False

        return True

    def flush_buffer(self):
        '''
        clears the buffer and returns any data taht was inside it
        :return: str
        '''
        buffered_data = ''

        for pkt in self.pkt_buffer:
            if (pkt is not None):
                buffered_data += pkt.payload

        self.pkt_buffer = [None for _ in range(self.src_max_window_size)]
        return buffered_data

    def resend_packets(self):
        '''
        Checks the timers on all unacked packets that were sent. If any have timed out, the packet will be resent. After
        a max amount of attempts, this will close the connection with the assumption that the client has crashed.
        :return: None
        '''
        for index in self.timers:
            if datetime.datetime.now() - self.timers[index]['time'] > datetime.timedelta(seconds=self.timeout):
                if self.timers[index]['retransmissions'] == self.max_retransmissions:
                    print 'Max retransmission count reached. Assuming client failure.'
                    self._reset_properties()
                else:
                    self._send_raw_packet(self.timers[index]['packet'], True)

    def send(self, data):
        '''
        Splits the data into packets and sends them all individually using _send_raw_packet
        :param data: str
        :return: None
        '''
        packetizer = PacketIterator( data.upper(), self.dst_max_window_size, self.get_seq_num )

        for packet in packetizer:
            print 'Sending back data.'
            self._send_raw_packet(packet)

    def _send_raw_packet(self, packet, retransmit=False):
        '''
        This will send packet over the connected client and start a timer for recieving the ack.
        :param packet:
        :param retransmit:
        :return:
        '''
        self.out_socket.sendto(packet, self.dst_ip_address)
        sent = Packet(packet)

        if retransmit:
            sent.add_flag(RETRANSMIT_FLAG)
            print 'Re-sending unacknowledged data.'

        if sent.is_nudge():
            seq_num = 'NUDGE'
        else:
            seq_num = str(sent.seq_num)

        if self.timers.get(seq_num):
            self.timers[seq_num]['time']             = datetime.datetime.now()
            self.timers[seq_num]['retransmissions'] += 1
        else:
            self.timers[seq_num] = {
                'time'            : datetime.datetime.now(),
                'packet'          : packet,
                'retransmissions' : 0
            }

    def disconnect( self, packet ):
        '''
        Should be called on every step of the teardown process, will handle resetting the servers properties and
        tearing down the connection
        :param packet:
        :return: None
        '''

        if self.on_teardown == 0:
            if packet.is_close():  
                print 'Attempting to disconnect from ' + str( self.dst_ip_address ) + ':' + str( self.port ) + '...'
              
                closeack = CLOSEACK(packet.seq_num)
                self.out_socket.sendto(closeack, self.dst_ip_address)
                
                close = CLOSE(self.get_seq_num())
                self._send_raw_packet(close)

                self.on_teardown = 1
        elif self.on_teardown == 1:
            if packet.is_close() and packet.is_ack():
                self.on_teardown = 2
                self._reset_properties()
                print 'Disconnected.'

    def _reset_properties(self):
        '''
        resets the servers properties so that its ready for a new connection.
        :return: None
        '''
        self.dst_ip_address = None
        self.dst_max_window_size = None
        self.on_handshake = 0
        self.on_teardown  = 0

        self.seqs_recd = []

        self.seqs_sent = []
        self.timers = {}

        self.pkt_buffer = [None for _ in range(self.src_max_window_size)]

        self.on_seq = 0

        Reldat.ind_start = 0
        Reldat.all_data = ""

    def has_connection(self):
        '''
        indicates whether the server is connected to a client.
        :return: boolean
        '''
        return self.on_handshake == 2
