#!/usr/bin/python

import socket
import sys

from packet import HeaderCorruptedError, PayloadCorruptedError
import reldat

'''
Main loop where the server listens for packets and
transmits packets if necessary.
'''
def listen_loop( reldat_conn ):
    while True:
        try:
            # Listen for a packet and possibly send packets
            reldat_conn.listen()
        except KeyboardInterrupt:
            # If someone Ctrl+C's the server, gracefully exit
            break
        except HeaderCorruptedError, PayloadCorruptedError:
            # Do nothing if a received packet is corrupted
            pass

        # If there are packets that need to be re-sent, re-send them
        reldat_conn.resend_packets()
        
        # Check our connectivity to the client
        reldat_conn.check_connection()

'''
Print server usage and quit.
'''
def usage():
    print 'Usage: ./reldat-server.py <port> <max receive window size in packets>'
    sys.exit( 0 )

'''
Main function.
'''
def main( argv ):
    if len( argv ) != 2:
        usage()

    # Port is the first parameter
    port = int( argv[0] )
    
    # Our max receive window size is the second parameter
    max_receive_window_size = int( argv[1] )

    # If the port number is too large to be a port, assume the user made a mistake
    if port > 65535:
        usage()

    # Open a RELDAT connection with our max window size
    reldat_conn = reldat.Reldat( max_receive_window_size )
    
    # Open our UDP sockets for I/O
    reldat_conn.open_socket( port )
    
    # Enter the listen loop
    listen_loop( reldat_conn )

if __name__ == '__main__':
    main( sys.argv[1:] )

sys.exit( 0 )
