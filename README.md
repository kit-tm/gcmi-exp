# GCMI Experiment Repository (for Reproducibility)

This repository contains a series of advanced experiments for the GCMI framework (https://github.com/kit-tm/gcmi). The experiments are shipped in vagrant containers and can be executed fully automated. Each experiment only requires a few calls to the command line to support easy reproducibility. This readme file contains a brief description of the experiments, a quick start guide and a guide for manual installation. The repository also contains the raw data of multiple runs for both experiments (including the plots that were generated). 

## Quick Start

The automated experiment environment supports Linux, macOS and Windows. It depends on Vagrant and VirtualBox, which need to be installed beforehand. Excecute the following commands to start the experiments:

1) Install VirtualBox, if required (https://www.virtualbox.org/)
2) Install Vagrant, if required (https://www.vagrantup.com/)

Then execute the following commands to run the PBCE experiment:
```
$ wget todo/gcmi-exp-pbce.box
$ vagrant add --name pbce gcmi-exp-pbce.box
$ vagrant init pbce
$ vagrant up
$ vagrant ssh
vagrant@ubuntu-xenial:~$ scripts/test_all.sh
```

To run the combined experiments with PBCE and TableVisor, execute the following commands:

```
$ wget todo/gcmi-exp-combined.box
$ vagrant add --name combined gcmi-exp-combined.box
$ vagrant init combined
$ vagrant up
$ vagrant ssh
vagrant@ubuntu-xenial:~$ scripts/test_all.sh
```

The experiments are executed automatically. A full run will take approx. 30 minutes. The results and log files will be put into the folder where `vagrant init` was executed. If you do not want to download the pre-built boxes (1.5GB), you can follow the manual instructions below to create the boxes from source. 

## Detailed Experiment Description

There are currently two fully automated experiments (pbce and combined) which can be found in their respective directories. The following subsections describe the two experiments and includes pointers to important code files.

### PBCE Experiment

The pbce experiment runs the PBCE GCMI-App in a three-switch test topology. This topology consists of a delegation switch (DS), an extension switch (ES) and a traffic switch (TS). TS is connected to DS through 16 links. It mediates traffic from 16 hosts to DS. Furthermore, ES is connected to DS. DS and ES are connected to the PBCE GCMI-App implemented in https://github.com/kit-tm/gcmi-exp/blob/master/pbce/tml/composer/src/main/java/com/github/sherter/jcon/composer/Pbce2Layer.java. 

This app uses the same threshold based heuristic that is used in https://ieeexplore.ieee.org/document/7809656. PBCE is supposed to extend the flow table capacity of DS through ES. PBCE as well as TS are connected to a Ryu controller which runs a modified learning switch (https://github.com/kit-tm/gcmi-exp/blob/master/pbce/apps/flow_switch_13.py). This regular app reactively installs end-to-end flows to DS to generate a high number of flows. Traffic is generated with iperf3 with randomly chosen host pairs. Flow bursts are modeled by varying the number of parallel streams for newly generated iperf3 sessions (which are in turn
generated at a constant rate, see https://github.com/kit-tm/gcmi-exp/blob/master/pbce/scripts/test.py).

### Combined PBCE/TableVisor Experiment

Todo


## Manual Setup

This repository contains dependencies to mininet, the Ryu controller framework and the core code base of GCMI. All required submodules that are needed to create the automated evaluation environments can be cloned with a single command. To build the vagrant boxes from source, execute the following commands:

```
$ git clone --recurse-submodules git@github.com:kit-tm/gcmi-exp.git`
$ cd gcmi-exp
$ cd pbce
$ ./package.sh
$ cd ../combined
$ ./package.sh
```

This will create the two vagrant box files (gcmi-exp-pbce.box and gcmi-exp-combined.box) from the quick start guide above. To run the box file after creation, execute the following commands:

```
$ cd gcmi-exp/pbce
$ vagrant add --name pbce gcmi-exp-pbce.box
$ mkdir runexp
$ cd runexp
$ vagrant init pbce
$ vagrant up
$ vagrant ssh
vagrant@ubuntu-xenial:~$ scripts/test_all.sh
```

and

```
$ cd gcmi-exp/combined
$ vagrant add --name combined gcmi-exp-combined.box
$ mkdir runexp
$ cd runexp
$ vagrant init combined
$ vagrant up
$ vagrant ssh
vagrant@ubuntu-xenial:~$ scripts/test_all.sh
```

## Log Files and Experiment Output

This test_all.sh scripts will automatically execute several tests. These tests are parameterized in pbce/scripts and pbce/configs. The latter contains the configuration for the GCMI-Apps (in case you want to change some parameters).

During the tests, logfiles in the top level directory (the directory where `vagrant init` was executed) should indicate the progress. These logfiles are named after the tests and contain the flow table utilization counters and other log information. Example:

test_1_ds.csv --> first column is time, second column is current flow table utilization of delegation switch (DS)
test_1_es.csv --> first column is time, second column is current flow table utilization of extension switch (ES)
test_1_mininet.log --> output of mininet during experiment
test_1_tml.log --> output of GCMI related stuff during experiment
test_1_ryu.log --> output of ryu during experiment

## Archive of Raw Experiment Results

The raw results of several experiments can be found in the raw_data directory. 



