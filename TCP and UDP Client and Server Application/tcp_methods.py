import struct
import socket

buf_size = 16
'''
Sends the message through the socket.
Makes sure that the message is received by the receiving end too.

@params
sock = socket instance
message = message you're trying to send
buf = buffer size to use for the message


@returns the message that was received by the receiving end of the socket
'''
def send_message(sock, message, buf):
    global buf_size
    buf_size = buf

    sock.sendall(message)
    verification_str = ""

    amount_received = 0
    amount_expected = len(message)

    while (amount_received < amount_expected):
        data = sock.recv(buf_size)
        if (data):
            verification_str += data
            amount_received += len(data)

    return verification_str

'''
Receives the message sent through the socket.
Sends the packets back to the sender for verification of proper receipt.

@params
sock = sock instance
'''
def receive_message(sock):
    data_str = ""
    receiving = True

    while (receiving):
        global buf_size
        data = sock.recv(buf_size)
        if (data):
            sock.sendall(data)
            data_str += data

            if (len(data) < buf_size):
                receiving = False

    return data_str
