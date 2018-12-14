#! /bin/bash
set -e

source config.sh

RYU_LOG_LEVEL="22"
DURATION="100"

MEASUREMENT=$1

CONTROLLER=$2
LAYER_TYPE=$3
NUM_LAYERS=$4
SWITCHES=$5


CONTROLLER_LOG=$PWD/$CONTROLLER\_$LAYER_TYPE\_$NUM_LAYERS\_$SWITCHES.log
CBENCH_LOG=$PWD/cbench_$CONTROLLER\_$LAYER_TYPE\_$NUM_LAYERS\_$SWITCHES.log

function kill_jobs {
    ssh $SSH_CONTROLLER -C "fuser -k 6653/tcp || true"
    ssh $SSH_TISCO -C "fuser -k 8080/tcp || true" || true
}

trap 'kill 0' INT
function cleanup {
    echo "killing all background jobs"
    kill_jobs
}
trap cleanup EXIT

kill_jobs

echo "clearing potential output files"
rm -f $CONTROLLER_LOG $CBENCH_LOG

echo "starting $CONTROLLER"
if [ "$CONTROLLER" = "ryu" ]; then
    ssh $SSH_CONTROLLER -C "ryu-manager --default-log-level $RYU_LOG_LEVEL ryu/ryu/app/simple_switch.py" < /dev/null &> $CONTROLLER_LOG &
elif [ "$CONTROLLER" = "floodlight" ]; then
    ssh $SSH_CONTROLLER -C "cd floodlight; java -jar target/floodlight.jar -cf src/main/resources/learningswitch.properties" < /dev/null &> $CONTROLLER_LOG &
elif [ "$CONTROLLER" = "onos" ]; then
    ssh $SSH_CONTROLLER -C "source onos_env.sh; cd onos; onos-buck run onos-local" < /dev/null &> $CONTROLLER_LOG &
    ssh $SSH_CONTROLLER -C "source onos_env.sh; ./onos.sh"
else
    echo "error: unsupported controller"
    exit 1
fi
ssh $SSH_CONTROLLER -C "until nc -z localhost 6653; do sleep 0.1; done;"

if (( $NUM_LAYERS > "-1" )); then
    echo "starting transparent middle layer"
    ssh $SSH_TISCO -C "rm -f ~/tml.log; cd ma-herter; ./gradlew run > ~/tml.log" < /dev/null &> /dev/null &
    ssh $SSH_TISCO -C "until nc -z localhost 8080; do sleep 0.1; done"
    ssh $SSH_TISCO -C "./configure_tisco.py $LAYER_TYPE $NUM_LAYERS $TISCO_ADDRESS $CONTROLLER_ADDRESS"
    CONNECT=$TISCO_ADDRESS
else
    CONNECT=$CONTROLLER_ADDRESS
fi

echo "starting cbench"
ssh $SSH_CBENCH -C "cbench -l $DURATION -m 1000 -c $CONNECT -p 6653 -s $SWITCHES" &> $CBENCH_LOG

./cbench_parse.py $CBENCH_LOG $MEASUREMENT $CONTROLLER $LAYER_TYPE $NUM_LAYERS $SWITCHES
