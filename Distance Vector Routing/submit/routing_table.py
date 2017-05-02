from copy import deepcopy
BASIC_PROTOCOL = 0
SPLIT_HORIZON_PROTOCOL = 1
SPLIT_HORIZON_WITH_POISON_REVERSE_PROTOCOL = 2

class RoutingTable:
    def __init__(self, N, router_num, protocol):
        self._N = N
        self.protocol = protocol
        self._table = {}
        # Belief of Links
        self._router_num = router_num
        self._distance_vector = {}
        self._neighbors = {}
        for i in range(1, self._N + 1):
            self._distance_vector[i] = VectorEntry()
        self._distance_vector[self._router_num] = VectorEntry(next_hop=self._router_num, cost=0, hops=0)

    def get_distance_vector_for_neighbor(self, n):
        '''
        Returns a distance vector meant for the neighbor n. This will return a distance vector that fits the algorithm
        being run (detemined by self.protocol).
        :param n: int
        :return: distance vector
        '''
        dv = {}
        for i in self._distance_vector:
            if (self.protocol == SPLIT_HORIZON_PROTOCOL):
                if self._distance_vector[i].next_hop != n:
                    dv[i] = self._distance_vector[i]
            elif (self.protocol == SPLIT_HORIZON_WITH_POISON_REVERSE_PROTOCOL):
                dv[i] = self._distance_vector[i] if self._distance_vector[i].next_hop != n else VectorEntry()
            else:
                dv[i] = self._distance_vector[i]
        return dv

    def set_neighbor(self, neighbor, cost):
        '''
        Sets the cost of a link with a neighbor
        :param neighbor: int
        :param cost: int
        :return: None
        '''
        if (cost == -1):
            if (self._neighbors.get(neighbor) is not None):
                del self._table[neighbor]
        else:
            if (self._neighbors.get(neighbor) is None):
                self._table[neighbor] = {}
                self._distance_vector[neighbor] = VectorEntry()
            self._neighbors[neighbor] = cost

    def set_neighbor_vector(self, n, distance_vector):
        '''
        Records the distance vector sent over by a neighbor, n
        :param n: int
        :param distance_vector: distance vector
        :return: None
        '''
        self._table[n] = distance_vector

    def update_distance_vector(self):
        '''
        Recalculates the distance vector of this routing table based on available information
        :return: None
        '''
        for i in self._distance_vector:
            if (i is self._router_num):
                continue

            possible_entries = []
            for n in self._neighbors:
                if (self.get_cost(n, i) != -1):
                    possible_entries.append(VectorEntry(next_hop=n, cost=self._neighbors[n] + self.get_cost(n, i),
                                                        hops= 1 + self.get_hops(n,i)))

            if (len(possible_entries)):
                self._distance_vector[i] = min(possible_entries)
                if self._distance_vector[i].next_hop == i:
                    self._distance_vector[i].hops = 1
                    self._distance_vector[i].cost = self._neighbors[i]
            else:
                self._distance_vector[i] = VectorEntry()

    def get_cost(self, src, dst):
        '''
        Returns the known cost from source to destination
        :param src: int
        :param dst: int
        :return: int
        '''
        if (self._table.get(src) is not None):
            if (self._table[src].get(dst) is not None):
                return self._table[src][dst].cost
        return -1

    def get_hops(self, src, dst):
        '''
        Records the known hops from src to dst
        :param src: int
        :param dst: int
        :return: int
        '''
        if (self._table.get(src) is not None):
            if (self._table[src].get(dst) is not None):
                return self._table[src][dst].hops
        return -1


    def __str__(self):
        s = str(self._router_num) + '\t'
        for i in range(1,self._N+1):
            s += '%s,%s \t' % (self._distance_vector[i].next_hop, self._distance_vector[i].hops)
        return s

class VectorEntry:
    def __init__(self, cost=-1, next_hop=-1, hops =-1):
        self.cost = cost
        self.next_hop = next_hop
        self.hops = hops

    def __gt__(self, other):
        return self.cost > other.cost

    def __lt__(self, other):
        return self.cost < other.cost

    def __str__(self):
        return str(self.cost)

    def __eq__(self, other):
        return self.cost == other.cost and self.next_hop == other.next_hop and self.hops == other.hops