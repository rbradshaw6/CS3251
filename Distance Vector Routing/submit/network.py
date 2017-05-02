from router import Router
from event import file_to_events
from copy import deepcopy
from collections import defaultdict

protocol_str = ['Basic Distance Vector Routing', 'Split Horizon', 'Split Horizon with Poison']

class Network(object):
    def __init__(self, N, real_routing_values, protocol, detailed=True):
        # real_routing_values only contains neighbors/real edges in the system.
        self.N              = N
        self.detailed = detailed
        self.real_routing_values  = real_routing_values
        self._events        = []
        self.routers        = {}
        self.details = ''
        self.count_to_infinity_error = False
        self.protocol = protocol
        for i in range(1, N + 1):
            self.routers[i] = Router(i, N, protocol)

        # setting neighbors
        for i in self.real_routing_values:
            for j in self.real_routing_values:
                self.routers[i].set_neighbor(self.routers[j], self.real_routing_values[i][j])

    def add_events(self, events):
        '''
        Adds events to the list of events and sorts the list based on time
        :param events: [Event...]
        :return: None
        '''
        self._events += events
        self._events.sort()

    def _enact_event(self, event):
        '''
        Makes the event happen. Sets the cost of the edge between the two routers.
        :param event: Event
        :return: None
        '''
        n1, n2 = event.link
        self.real_routing_values[n1][n2] = event.cost
        self.real_routing_values[n2][n1] = event.cost
        self.routers[n1].set_neighbor(self.routers[n2], event.cost)
        self.routers[n2].set_neighbor(self.routers[n1], event.cost)

    def simulate(self):
        '''
        Repeatedly runs the distance vector algorithm until the routers have convereged and no events are waiting to
        happen. Will take care of adding details to self.details
        :return: None
        '''
        converged = False
        t = 1
        printing_state = 0
        last_event_time = 0

        while (len(self._events) or not converged):
            converged = True

            while len(self._events) and self._events[0].time == t:
                e = self._events.pop(0)
                self._enact_event(e)
                last_event_time = t
            self._add_str('Round ' + str(t) + "\n-----------------------------------------------------------")
            for i in self.routers:
                self.routers[i].send_distance_vector_to_neigbors()

            printed = {}
            for i in self.routers:
                self._add_str('\tRouter ' + str(i) + ":")
                if (self.detailed):
                    if (i not in printed):
                        self._add_details("")
                        self._add_str("\n")
                        printed[i] = True
                change = self.routers[i].update_distance_vector()
                if (change):
                    converged = False
                    printing_state = 0


            printing_state += 1
            t += 1
            if (t - last_event_time >= 100):
                self.details += "\nCount to infinity error\n"
                self.count_to_infinity_error = True
                break
        self.convergence_delay = str(t - last_event_time -1)
        self._add_details(protocol_str[self.protocol] + ' (Final Output):\n')

    def _add_details(self, s):
        '''
        self.details corresponds to the detailed output for this network. This method adds each router's to_string to
        the details as well as the passed in string
        :param s: string
        :return: None
        '''
        self.details += s
        for i in self.routers:
            self.details += "\t" + str(self.routers[i]) + '\n'

    def _add_str(self, s):
        '''
        adds this string to the details
        :param s: str
        :return: None
        '''
        self.details += s + '\n'

    def get_output(self):
        '''
        Returns the final output which contains the routing table for each router.
        :return: str
        '''
        s = ""
        for i in self.routers:
            s += str(self.routers[i]) + '\n'
        if (not self.count_to_infinity_error):
            s += 'Convergence Delay: ' + self.convergence_delay + " rounds.\n"
        else:
            s += 'Count to infinity error encountered\n'
        return s

def files_to_network(network_file, events_file, detailed):
    '''
    Given the network file, events file and the detailed flag, this will run the distance vector algorithm
    3 times (once for each variation of the algorithm) and return an array of networks that have converged.
    :param network_file: file
    :param events_file: file
    :param detailed: boolean
    :return: [Network...]
    '''
    lines = network_file.read().splitlines()
    network_file.close()
    N = int(lines[0])
    real_routing_values = {}
    for i in range(1,N+1):
        real_routing_values[i] = {}
        for j in range(1, N + 1):
            real_routing_values[i][j] = -1 * int(i != j)

    for line in lines[1:]:
        n1, n2, c = [int(x) for x in line.split(" ") if (len(x) > 0)]
        real_routing_values[n1][n2] = c
        real_routing_values[n2][n1] = c

    events = file_to_events(events_file)
    events_file.close()
    networks = []
    for i in range(3):
        n = Network(N, deepcopy(real_routing_values), i, detailed)
        n.add_events(deepcopy(events))
        n.simulate()
        networks.append(n)
    return networks
