import sys

from network import files_to_network

def main(argv):
    if (len(argv) != 3):
        print "Usage: ./p3.py <topology filename> <event change filename> <binary flag>"
        sys.exit(0)

    topology_file_name = argv[0]
    event_change_file_name = argv[1]
    detailed = int(argv[2])
    networks = files_to_network(open(topology_file_name), open(event_change_file_name), detailed)
    f = open('./output.txt', 'w+')
    f2 = open('./detailed-output.txt','w+')
    intro_texts = ['Basic Distance Vector Routing', 'Split Horizon', 'Split Horizon with Poison']
    for n in range(3):
        f.write(intro_texts[n])
        f.write("\n------------------------\n")
        f.write(networks[n].get_output())
        if (detailed):
            f2.write(intro_texts[n] + "\n-----------------------------------------------------------\n")
            f2.write(networks[n].details)
            #f2.write(networks[n].get_output())
            f2.write('\n \n \n \n')
        f.write('\n')
    f.close()

if (__name__ == "__main__"):
    main(sys.argv[1:])

sys.exit(0)
