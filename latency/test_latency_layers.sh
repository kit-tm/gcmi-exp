#! /bin/bash
set -e

CONTROLLER=$1
LAYER_TYPE="log_and_forward"
SWITCHES="1"

MEASUREMENT=$PWD/measurement_latency_$CONTROLLER\_$LAYER_TYPE\_$SWITCHES.csv

echo "clearing previous measurements"
rm -f $MEASUREMENT

for NUM_LAYERS in -1 0 1 2 4 8 16 32
do
    ./test.sh $MEASUREMENT $CONTROLLER $LAYER_TYPE $NUM_LAYERS $SWITCHES
done    

#./plot.py $MEASUREMENT plot_latency_$CONTROLLER\_$LAYER_TYPE\_$SWITCHES.pdf 2
