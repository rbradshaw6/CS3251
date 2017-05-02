import socket
import sys
print sys.argv
from tcp_methods import *


def main():
    args = sys.argv
    if (len(args) < 4):
        print "Invalid arguments."
        return

    server_ip_address = sys.argv[1]
    server_port_no = sys.argv[2]
    sms_txt_file_name = sys.argv[3]

    sms = ""
    with open(sms_txt_file_name, 'r') as f:
        sms = f.read().replace("\n", " ")

    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_address = (server_ip_address, int(server_port_no))
    sock.connect(server_address)

    send_message(sock, sms, 16)
    ret = receive_message(sock)
    print ret

    sock.close()

main()
