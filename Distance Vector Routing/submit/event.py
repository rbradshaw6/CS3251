class Event:
    '''
    This class represents and event that can occur. It has three properties,
    time
    link
    cost
    and implements comparison operators that are based on time.
    '''
    def __init__(self, event_arr):
        self.time = event_arr[0]
        self.link = (event_arr[1], event_arr[2])
        self.cost = event_arr[3]
    def __gt__(self, other):
        return self.time > other.time

    def __lt__(self, other):
        return self.time < other.time

def file_to_events(file):
    '''
    Given an events file, this will return an array of Event objects.
    :param file:
    :return:
    '''
    lines = file.read().splitlines()
    events = []
    for line in lines:
        events.append(Event([int(i) for i in line.split(" ")]))
    return events
