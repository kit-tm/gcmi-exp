#!/usr/bin/python

import os
import random
import time
import simpy.rt
import subprocess
from mininet.clean import cleanup
from mininet.log import setLogLevel
from mininet.net import Mininet
from mininet.cli import CLI
from mininet.node import RemoteController
from mininet.node import OVSSwitch
from mininet.topo import Topo

iperf_bytes = 550000
iperf_bandwidth = 3500000
time_range = 400
epoch = [0,1,1,2,4,5,4,3,4,2,1,1,1,0,0,1]
epoch_offset = 1
inter_flow = [3]

class PBCETopo(Topo):
    def build(self, num_links=1, num_hosts_per_link=1):
        delegationSwitch = self.addSwitch("ds", cls=OVSSwitch, dpid="1")
        trafficSwitch = self.addSwitch("ts", cls=OVSSwitch, dpid="2")
        extensionSwitch = self.addSwitch("es", cls=OVSSwitch, dpid="3")

        self.addLink(extensionSwitch, delegationSwitch, 1, num_links + 1)
        for i in range(num_links):
            self.addLink(delegationSwitch, trafficSwitch, i + 1, i + 1)  
            for j in range(num_hosts_per_link):
                host = self.addHost(
                    "h_{}_{}".format(i, j),
                    mac="02:00:00:00:{:02x}:{:02x}".format(i, j))
                self.addLink(
                    trafficSwitch, host,
                    num_links + i * num_hosts_per_link + j + 1, 0)

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
        self.i = i
        self.j = j
        self.target_hosts = [(k, l)
                             for k in range(self.tg.num_links)
                             for l in range(self.tg.num_hosts_per_link)
                             if self.i != k]
        self.action = self.tg.env.process(self.run())

    def run(self):
        yield self.tg.env.timeout(random.choice(inter_flow) * random.random())
        time_now = self.tg.env.now
        while time_now < time_range:
            epoch_index = int((time_now * len(epoch))/time_range)
            flows = epoch[epoch_index] + epoch_offset

            for _ in range(flows):
                k, l = random.choice(self.target_hosts)
                self.tg.iperf(
                    self.i, self.j, k, l, iperf_bytes, iperf_bandwidth)

            yield self.tg.env.timeout(random.choice(inter_flow))
            time_now = self.tg.env.now

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
        time_now = 0
        while time_now < time_range:
            subprocess.Popen(
                ["scripts/active.sh {} {} {}".format(self.name, time_now, self.f)],
                shell=True,
                stdin=self.devnull, stdout=self.devnull, stderr=self.devnull)
            yield self.env.timeout(1.0)
            time_now = self.env.now

def clean():
    os.system("pkill iperf3")
    cleanup()

def main():
    num_links = 16
    num_hosts_per_link = 1

    cleanup()
    net = Mininet(
        topo=PBCETopo(
            num_links=num_links,
            num_hosts_per_link=num_hosts_per_link),
        controller=RemoteController("controller", ip='127.0.0.1', port=6654),
        autoStaticArp=True,
        waitConnected=True
    )

    try:
        net.start()
        cli = False
        if (cli):
            CLI(net)
        else:
            env = simpy.rt.RealtimeEnvironment(strict=True)
            TrafficGenerator(env, net, num_links, num_hosts_per_link)
            TableObserver(env, "es")
            TableObserver(env, "ds")
            TableObserver(env, "ts")
            env.run()
    except KeyboardInterrupt:
        pass
    except RuntimeError:
        open('__mn_slow', 'a').close()
    finally:
        net.stop()
        del net
        clean()
        os.remove('__mn_running')

if __name__ == '__main__':
    # Tell mininet to print useful information
    setLogLevel('info')
    main()
