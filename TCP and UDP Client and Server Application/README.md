# Networking Project 1

Robert Bradshaw
rbradshaw6@gatech.edu
CS 3251 - Coding Assignment 1 (TCP and UDP)
2/8/2017

To run the server:
    ``` python smsengineTCP.py <port> <suspicious words file name>```
    - As an example, locally I run:
        ```python smsengineTCP.py 8591 suspicious_words.txt)```
To run the client:
    ```python smsclientTCP.py <IP> <port> <message file name>```
    - As an example, locally I run:
    ```python smsclientTCP.py robert 8591 msg.txt)```

Runnable files:
    - smsengineTCP.py - runs server with configuration specified by the arguments -- sends spam score to client when message is fully received (with TCP)
    - smsclientTCP.py - sends message to server and gets the spam score back (with TCP)
    - smsengineUDP.py - runs server with configuration specified by the arguments -- sends spam score to client when message is fully received (with UDP)
    - smsclientUDP.py - sends message to server and gets the spam score back (with UDP)

Helper files:
    - tcp_methods.py - contains the methods used to send and receive methods using TCP (since they have to be accessible by both client and server I put them in one python file and have both client and server python files import it.)
    - score_calculation.py - Calculates the spam score of the message (since the score calculation method has to be accessible by both TCP and UDP implementations I put it in this one python file that they both import).
