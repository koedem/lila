import time
import os
import random
import subprocess
import multiprocessing as mp
from modules.parse import modules


def init():
    mp.set_start_method("spawn")


def make(modname: str) -> None:
    module = modules[modname]
    build = module["build"]
    # lets just run all the scripts for now

    try:
        for key in build.keys():
            buildlist = build[key]
            for cmdlist in buildlist:
                result = subprocess.run(
                    cmdlist,
                    cwd=module["cwd"],
                    # stderr=subprocess.STDOUT,
                    # stdout=subprocess.PIPE,
                    # input=password.encode("utf-8"),
                )
                result.check_returncode()
                print(f"{result.stdout}")
    except BaseException as err:
        print(f"{modname} {err}")


class Cmd:
    def __init__(self, args: list, cwd: str):
        self.args = args
        self.cwd = cwd
        pass


class Work:
    def __init__(self, id: str, cmd: Cmd):
        result = subprocess.run(
            cmd.args,
            cwd=cmd.cwd,
            stdout=subprocess.PIPE,
            # input=password.encode("utf-8"),
        ).stdout
        print(f"Got: {result}")


class WorkChain:
    def __init__(self, root: Work, deps: list = []):
        pass


class WorkQueue:
    queue = mp.Queue()
    lock = mp.Lock()

    def __init__(self):
        None

    def add(self, work_chain: WorkChain):
        # eventually traverse the chain to consolidate redundancies in the queue
        self

    def next(self) -> Work:
        with lock:
            None

    def success(module: str):
        with lock:
            None

    def error(module: str, text: str):
        with lock:
            None


def work(queue, lock):
    while True:
        build_cmd = queue.get()
        result = subprocess.run(
            work_item.cmd,
            stdout=subprocess.PIPE,
            # input=password.encode("utf-8"),
        ).stdout


"""
# Producer function that places data on the Queue
def producer(queue, lock, names):
    # Synchronize access to the console
    with lock:
        print("Starting producer => {}".format(os.getpid()))

    # Place our names on the Queue
    for name in names:
        time.sleep(random.randint(0, 10))
        queue.put(name)

    # Synchronize access to the console
    with lock:
        print("Producer {} exiting...".format(os.getpid()))


# The consumer function takes data off of the Queue
def consumer(queue, lock):
    # Synchronize access to the console
    with lock:
        print("Starting consumer => {}".format(os.getpid()))

    # Run indefinitely
    while True:
        time.sleep(random.randint(0, 10))

        # If the queue is empty, queue.get() will block until the queue has data
        name = queue.get()

        # Synchronize access to the console
        with lock:
            print("{} got {}".format(os.getpid(), name))


def ima_piece_of_lumber():  # if __name__ == '__main__':

    # Some lists with our favorite characters
    names = [
        ["Master Shake", "Meatwad", "Frylock", "Carl"],
        ["Early", "Rusty", "Sheriff", "Granny", "Lil"],
        ["Rick", "Morty", "Jerry", "Summer", "Beth"],
    ]

    # Create the Queue object
    queue = Queue()

    # Create a lock object to synchronize resource access
    lock = Lock()

    producers = []
    consumers = []

    for n in names:
        # Create our producer processes by passing the producer function and it's arguments
        producers.append(Process(target=producer, args=(queue, lock, n)))

    # Create consumer processes
    for i in range(len(names) * 2):
        p = Process(target=consumer, args=(queue, lock))

        # This is critical! The consumer function has an infinite loop
        # Which means it will never exit unless we set daemon to true
        p.daemon = True
        consumers.append(p)

    # Start the producers and consumer
    # The Python VM will launch new independent processes for each Process object
    for p in producers:
        p.start()

    for c in consumers:
        c.start()

    # Like threading, we have a join() method that synchronizes our program
    for p in producers:
        p.join()

    print("Parent process exiting...")
"""
