#!/bin/bash
set -e

tar -chf up.tar apps configs scripts ryu.patch --exclude='examples/flowvisor/flowvisor' tml -C ../ mininet ryu

vagrant up
vagrant package --vagrantfile Vagrantfile-box --output gcmi-exp-combined.box
vagrant destroy -f

rm up.tar
