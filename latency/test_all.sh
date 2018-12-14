#! /bin/bash
set -e

./test_latency_layers.sh ryu
./test_latency_layers.sh floodlight
./test_latency_layers.sh onos

./plot.py plot.pdf 2 Ryu measurement_latency_ryu_log_and_forward_1.csv -0.25 Floodlight measurement_latency_floodlight_log_and_forward_1.csv +0.00 ONOS measurement_latency_onos_log_and_forward_1.csv +0.25
