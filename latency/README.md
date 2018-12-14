# GCMI Latency Experiment

## Installation

Three testbed servers are needed to conduct the experiment.
This section outlines the needed steps to setup each server.

- SDN Controller Server (SSERVER)
    - Prerequisites (Debian)
        ```
        $ apt-get install build-essential ant maven python-dev python-webob python-pip
        $ sudo pip install webob tinyrpc routes ovs oslo.config msgpack "eventlet!=0.18.3,!=0.20.1,<0.21.0,>=0.18.2"
        ```
    - Ryu Installation
        - Upload patch to server
        - Clone Ryu Repo and apply patch
        - Install Ryu
        ```
        $ rsync -a ryu.patch <SSERVER>:
        $ ssh <SSERVER>
        $ git clone https://github.com/osrg/ryu.git
        $ cd ryu
        $ git checkout 1c008060fa3dab51c3a59c1485a7529b13cf0dd1
        $ patch -p0 < ~/ryu.patch
        $ python setup.py install
        ```
    - Floodlight Installation
        - Upload patch to server
        - Clone Floodlight Repo and apply patch
        - Build Floodlight
        ```
        $ rsync -a floodlight.patch <SSERVER>:
        $ ssh <SSERVER>
        $ git clone --recurse-submodules https://github.com/floodlight/floodlight.git
        $ cd floodlight
        $ git checkout f8df353981332466eef19073b1dbfdee974c9579
        $ patch -p0 < ~/floodlight.patch
        $ ant
        ```
    - ONOS Installation
        - Upload files to server
        - Clone ONOS Repo and apply patch
        ```
        $ rsync -a onos.patch onos_env.sh onos.sh <SSERVER>:
        $ ssh <SSERVER>
        $ git clone https://github.com/opennetworkinglab/onos.git
        $ cd onos
        $ git checkout 9f0e15b2968efda9ce53c621432fb03259d80c6a
        $ patch -p0 < ~/onos.patch
        $ mvn clean install
        ```
        - ^ might fail due to maven-surefire plugin: ignore
- GCMI Server (GSERVER)
    - Prerequisites
        - Java 8
    - Install
        ```
        $ rsync -a configure_tisco.py <GSERVER>:
        $ ssh <GSERVER>
        $ git clone git@git.scc.kit.edu:stud/ma-herter.git
        $ cd ma-herter
        $ git checkout ac011fbbf0d1323091cadf12787d239dfd5702d8
        ```
- CBench Server (CSERVER)
    - Prerequisites (Debian)
        ```
        $ sudo apt-get install dh-autoreconf libconfig-dev libsnmp-dev libpcap-dev
        ```
    - Install
        ```
        $ rsync -a oflops.patch <CSERVER>:
        $ ssh <CSERVER>
        $ git clone https://github.com/mininet/openflow.git
        $ git clone https://github.com/mininet/oflops.git
        $ cd oflops
        $ git checkout 762d51786f88b6da834b3eee1e1ac7212ef31dea
        $ patch -p0 < ~/oflops.patch
        $ boot.sh
        $ ./configure
        $ make
        $ make install
        ```

## Configuration

Create a config.sh file which contains the following definitions:

```
SSH_CONTROLLER=<SSERVER>
SSH_TISCO=<GSERVER>
SSH_CBENCH=<CSERVER>

CONTROLLER_ADDRESS=<SSERVER_ADDRESS>
TISCO_ADDRESS=<GSERVER_ADDRESS>
CBENCH_ADDRESS=<CSERVER_ADDRESS>
```

SSH-Section (top): the hostnames under which the servers are reachable over ssh.
ADDRESS-Section (bottom): the addresses under which the servers can reach each other.
An example can be found under config.sh.example


## Usage

$ ./test_all.sh

After all tests have passed, a file named plot.pdf will be generated showing the latency results.
