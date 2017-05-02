import socket
import sys

print sys.argv
from tcp_methods import *
from score_calculation import *

def main():
    args = sys.argv
    if (len(args) < 3):
        print "Invalid arguments."
        return

    server_port_no = sys.argv[1]
    suspicious_words_txt_file = sys.argv[2]

    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    print socket.gethostname()
    server_address = (socket.gethostbyname(socket.gethostname()), int(server_port_no))
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(server_address)

    buffer_size = 16

    sock.listen(1)
    while (True):
        try:
            connection, client_addr = sock.accept()
            sms = receive_message(connection)
            ret = calculate_spam_score(sms, suspicious_words_txt_file)
            verification_str = send_message(connection, ret, buffer_size)
            if (verification_str != ret): #message was not received properly
                return "0 -1 ERROR"
            print verification_str
            running = False

        finally:
            connection.close()

main()
