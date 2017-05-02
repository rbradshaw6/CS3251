import socket
import sys

print sys.argv
from score_calculation import *


def main():
    args = sys.argv
    if (len(args) < 3):
        print "Invalid arguments."
        return

    server_port_no = sys.argv[1]
    suspicious_words_txt_file = sys.argv[2]

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    server_address = (socket.gethostbyname(socket.gethostname()), int(server_port_no))
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(server_address)

    try:
        while (True):
            data, address = sock.recvfrom(4096)
            if (data):
                sms = data
                trans = sock.sendto(data, address)
                ret = calculate_spam_score(sms, suspicious_words_txt_file)
                sock.sendto(ret, address)
    finally:
        sock.close()

main()
