import socket
import sys
print sys.argv
from tcp_methods import *
import time

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
        sms = f.read().replace('\n', '')

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    server_address = (server_ip_address, int(server_port_no))
    sock.settimeout(2)
    start_time = time.time()


    ret = ""
    attempts = 0
    try:
        running = True
        while (attempts < 3 and running):
            try:
                sent = sock.sendto(sms, server_address)
                verif, server = sock.recvfrom(4096)
                if (verif):
                    if (verif == sms):
                        print "Message received successfully"

                ret, server = sock.recvfrom(4096)
                running = False
            except socket.timeout:
                print "It has been 2 seconds... Attempting to send message again."
                elapsed_time = time.time() - start_time
                attempts += 1
        print ret
        return ret
    finally:
        if (attempts > 2):
            print "Tried 3 times. Ya done screwed up."
        else:
            print "Done!"
main()
