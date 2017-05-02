import re

'''
Calculates the spam score of the message sent to the server.

@params
sms - the message we are analyzing
suspicious_words_txt_file - the suspicious words text file to be opened

returns a tuple in the following form: (# of suspicious words, score, suspicious words found in the sms)
returns error message if:
    1) Length of message or # of suspicious words is 0.
    2) Any non-ascii characters occur in any word.
'''
def calculate_spam_score(sms, suspicious_words_txt_file):
    error = "0 -1 ERROR"

    if (len(sms) < 1 or len(sms) > 1000):
        return error
        
    sus = []
    with open(suspicious_words_txt_file, 'r') as f:
        sus = f.readlines()
        f.close()

    if (len(sus) < 1):
        return error

    sus = [x.strip() for x in sus]

    print "sms: " + str(sms)
    print "sus: " + str(sus)

    c = 0
    n = 0 #n =  # of empty strings and other things that wont be counted
    vio = []
    ret = ""

    sms = re.split('\W+', sms)

    for word in sms:
        word = word.replace("\n", "").replace("\r", "")
        if (len(word) < 1 or not word.isalpha()):
            n += 1
        for x in word:
            if (not is_char_ascii(x)):
                return error
        if (word in sus):
            c += 1
            vio.append(word)
            ret += word + " "

    if (len(vio) > 0):
        ret = ret[:len(ret) - 1]

    score = 0
    if (len(sms) > 0 and n <= len(sms)):
        score = float((c) / (float(len(sms)) - n))
    return "(" + str(len(sus)) + ", " + str(score) + ", " + str(ret) + ")"

'''
Speaks for itself, my dudes.
'''
def is_char_ascii(string):
    return (all(ord(char) < 128 for char in string))
