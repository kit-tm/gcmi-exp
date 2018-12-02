#!/usr/bin/python

import os
import random
import time
import simpy.rt
import subprocess
import sys
from mininet.clean import cleanup
from mininet.cli import CLI
from mininet.log import setLogLevel
from mininet.net import Mininet
from mininet.node import RemoteController
from mininet.node import OVSSwitch
from mininet.topo import Topo

iperf_bytes = 550000
iperf_bandwidth = 3500000
time_range = 400
epoch = [0,1,1,2,4,5,4,3,4,2,1,1,1,0,0,1]
epoch_offset = 1
inter_flow = [3]

class TrafficGenerator(object):
    def __init__(self, env, net, num_links, num_hosts_per_link):
        self.env = env
        self.net = net
        self.num_links = num_links
        self.num_hosts_per_link = num_hosts_per_link

        self.ports = {}
        self.devnull = open(os.devnull, 'w')
        #self.log = open("sync/log.out", 'w')

        # start traffic generators
        for i in range(self.num_links):
            for j in range(self.num_hosts_per_link):
                HostTrafficGenerator(self, i, j)

    def iperf(self, i, j, k, l, bytes, bandwidth):
        port = self.ports.get((k, l), 1024)
        self.ports[(k, l)] = port + 1
        #log_c = open("sync/{}_{}_{}_{}_{}_c.log".format(i, j, k, l, port), 'w')
        #log_s = open("sync/{}_{}_{}_{}_{}_s.log".format(i, j, k, l, port), 'w')
        log_c = self.devnull
        log_s = self.devnull
        self.getHost(k, l).popen(
            ["iperf3", "-s", "-p", str(port), "-1"],
            stdin=self.devnull, stdout=log_s, stderr=log_s)
        self.getHost(i, j).popen(
            ["iperf3", "-c", self.getHost(k, l).IP(), "-p", str(port),
             "-n", str(iperf_bytes), "-b", str(iperf_bandwidth)],
            stdin=self.devnull, stdout=log_c, stderr=log_c)

    def getHost(self, i, j):
        return self.net.get("h_{}_{}".format(i, j))

class HostTrafficGenerator(object):
    def __init__(self, tg, i, j):
        self.tg = tg
        self.iN = i + 1
        self.jN = j + 1
        self.target_hosts = [(k + 1, l + 1)
                             for k in range(self.tg.num_links)
                             for l in range(self.tg.num_hosts_per_link)
                             if i != k]
        self.action = self.tg.env.process(self.run())

    def run(self):
        yield self.tg.env.timeout(random.choice(inter_flow) * random.random())
        while True:
            epoch_index = int((self.tg.env.now * len(epoch))/time_range)
            flows = epoch[epoch_index] + epoch_offset

            for _ in range(flows):
                kN, lN = random.choice(self.target_hosts)
                self.tg.iperf(
                    self.iN, self.jN, kN, lN, iperf_bytes, iperf_bandwidth)

            yield self.tg.env.timeout(random.choice(inter_flow))

class TableObserver(object):
    def __init__(self, env, name):
        self.env = env
        self.name = name

        self.f = "sync/{}.csv".format(self.name)
        self.devnull = open(os.devnull, 'w')

        try:
            os.remove(self.f)
        except OSError:
            pass

        self.action = self.env.process(self.run())

    def run(self):
        while True:
            subprocess.Popen(
                ["scripts/active.sh {} {} {}".format(self.name, self.env.now, self.f)],
                shell=True,
                stdin=self.devnull, stdout=self.devnull, stderr=self.devnull)
            yield self.env.timeout(1.0)

def build_and_start_topology(net, with_tml, num_links, num_hosts_per_link):
    ryu = net.addController('ryu', ip='127.0.0.1', port=6653)
    routerSwitch = net.addSwitch("rs", dpid="1")
    trafficSwitch = net.addSwitch("ts", dpid="4")

    if with_tml:
        tml = net.addController('tml', ip='127.0.0.1', port=6654)

        t0Switch = routerSwitch
        t1Switch = net.addSwitch("t1", dpid="2")
        t2Switch = net.addSwitch("t2", dpid="3")

        net.addLink(t0Switch, t1Switch, 31, 30)
        net.addLink(t1Switch, t2Switch, 31, 30)
        net.addLink(t2Switch, t0Switch, 31, 30)

    for i in range(num_links):
        iN = i + 1
        net.addLink(routerSwitch, trafficSwitch, iN, iN)
        for j in range(num_hosts_per_link):
            jN = j + 1
            host = net.addHost(
                "h_{}_{}".format(iN, jN),
                mac="02:00:00:00:{:02x}:{:02x}".format(iN, jN),
                ip="10.0.{}.{}/24".format(iN, jN),
                defaultRoute="via 10.0.{}.254".format(iN))
            #host.setARP("10.0.{}.254".format(iN), "02:00:00:00:{:02x}:0".format(iN))
            net.addLink(
                trafficSwitch, host,
                num_links + i * num_hosts_per_link + jN, 1)

    net.build()

    for i in range(num_links):
        iN = i + 1
        for j in range(num_hosts_per_link):
            jN = j + 1
            host = net.get("h_{}_{}".format(iN, jN))
            host.setARP("10.0.{}.254".format(iN), "02:00:00:00:{:02x}:0".format(iN))

    ryu.start()
    trafficSwitch.start([ryu])
    if with_tml:
        tml.start()
        t0Switch.start([tml])
        t1Switch.start([tml])
        t2Switch.start([tml])
    else:
        routerSwitch.start([ryu])

    net.waitConnected()

def clean():
    os.system("pkill iperf3")
    cleanup()

def main():
    num_links = int(sys.argv[1])
    with_tml = bool(sys.argv[2] != "0")

    print("with_tml={}, num_links={}".format(with_tml, num_links))

    cleanup()
    net = Mininet(controller=RemoteController, switch=OVSSwitch)
    build_and_start_topology(net, with_tml, num_links, 1)

    try:
        cli = False
        if cli:
            CLI(net)
        else:
            env = simpy.rt.RealtimeEnvironment(strict=True)
            TrafficGenerator(env, net, num_links, 1)
            TableObserver(env, "rs")
            TableObserver(env, "t1")
            TableObserver(env, "t2")
            env.run(until=time_range)
    except KeyboardInterrupt:
        pass
    except RuntimeError:
        open('__mn_slow', 'a').close()
    finally:
        net.stop()
        del net
        clean()
        #os.remove('__mn_running')

if __name__ == '__main__':
    # Tell mininet to print useful information
    setLogLevel('info')
    main()
