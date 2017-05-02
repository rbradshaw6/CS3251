import sys
from routing_table import RoutingTable
from copy import deepcopy
class Router(object):
    def __init__(self, router_num, N, protocol):
        self._router_num = router_num
        self._N = N
        self._neighbors = {}
        self._routing_table = RoutingTable(N, self._router_num, protocol)

    def get_router_num(self):
        '''
        Returns the router num for this router
        :return: int
        '''
        return self._router_num

    def set_neighbor(self, neighbor, cost):
        '''
        If cost is -1, this will remove a link. Otherwise, this will set or reset the cost of a neighbor
        :param neighbor: Router
        :param cost: int
        :return: None
        '''
        if (neighbor is self):
            return
        if (cost == -1):
            if (self._neighbors.get(neighbor.get_router_num) is not None):
                del self._neighbors[neighbor.get_router_num()]
        else:
            self._neighbors[neighbor.get_router_num()] = neighbor
        self._routing_table.set_neighbor(neighbor.get_router_num(), cost)

    def receive_distance_vector(self, src, vector):
        '''
        Meant to be called by other routers that want to send over their distance vector. This router will store the
        vector and their source
        :param src: int
        :param vector: {}
        :return: None
        '''
        self._routing_table.set_neighbor_vector(src, vector)

    def send_distance_vector_to_neigbors(self):
        '''
        Sends this routers distance vector to all of its neighbors.
        :return: None
        '''
        for n in self._neighbors:
            dv = self._routing_table.get_distance_vector_for_neighbor(n)
            self._neighbors[n].receive_distance_vector(self.get_router_num(), dv)

    def update_distance_vector(self):
        '''
        Meant to be called after recieving all distance vector. Will recalculate the distance vector and return a bool
        indicating whether or not any changes where made.
        :return: boolean
        '''
        old_dv = deepcopy(self._routing_table.get_distance_vector_for_neighbor(-1))
        self._routing_table.update_distance_vector()
        dv = self._routing_table.get_distance_vector_for_neighbor(-1)
        return (dv != old_dv)

    def __str__(self):
        return str(self._routing_table)
