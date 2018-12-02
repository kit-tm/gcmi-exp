#!/bin/bash
set -e

tar -cf up.tar apps configs scripts tml -C ../ mininet ryu

vagrant up
vagrant package --vagrantfile Vagrantfile-box --output gcmi-exp-pbce.box
vagrant destroy -f

rm up.tar
