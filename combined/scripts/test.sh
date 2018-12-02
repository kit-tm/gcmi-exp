#! /bin/bash
set -e
function cleanup {
    echo "killing all background jobs"
    pkill --full java || true
    pkill --full python || true
    sudo mn -c || true
}
trap cleanup EXIT

TEST=$1
HOSTS=$2
TML=$3
THRES=$4
OUTD=$PWD/sync

echo "clearing potential output files"
rm -f __ryu_running $OUTD/${TEST}_ryu.log $OUTD/${TEST}_tml.log $OUTD/${TEST}_mininet.log

echo "starting ryu"
touch __ryu_running
ryu-manager apps/flow_switch_13.py --num-links $HOSTS < /dev/null &> $OUTD/${TEST}_ryu.log &
until nc -z localhost 6653; do sleep 0.1; done

if [ "$TML" -ne "0" ]; then
    echo "starting transparent middle layer"
    pushd tml
    ./gradlew run < /dev/null &> $OUTD/${TEST}_tml.log &
    until nc -z localhost 8080; do sleep 0.1; done
    popd

    echo "configuring transparent middle layer"
    curl -f -H "Content-Type: application/json" -X POST -d '
    {
        "type": "pbce2",
        "config": {
            "switches": ["1"],
            "lower": '$(($THRES - 100))',
            "upper": '$THRES',
            "upper2": '$(($THRES + 100))',
            "gracePeriod": 3.0
        }
    }
    ' localhost:8080/layers

    curl -f -H "Content-Type: application/json" -X POST -d '
    {
        "type": "tablevisor",
        "config": {
            "1": [
            "1",
            "2",
            "3"
            ]
        }
    }
    ' localhost:8080/layers

    curl -f -H "Content-Type: application/json" -X POST -d '
    {
        "source": { "address" : "127.0.0.1:6654" },
        "targets": [ { "layer" : 1 } ]
    }
    ' localhost:8080/edges

    curl -f -H "Content-Type: application/json" -X POST -d '
    {
        "source": { "layer" : 1 },
        "targets": [ { "layer" : 2 } ]
    }
    ' localhost:8080/edges

    curl -f -H "Content-Type: application/json" -X POST -d '
    {
        "source": { "layer" : 2 },
        "targets": [ { "address" : "127.0.0.1:6653" } ]
    }
    ' localhost:8080/edges
fi

echo "starting mininet"
sudo python scripts/test.py $HOSTS $TML

for f in $OUTD/{rs,t1,t2}.csv; do cp -- "$f" "$OUTD/${TEST}_$(basename $f)"; done

#sudo python scripts/test.py $1 $3 &> $OUTD/$3_mininet.log &

#echo "waiting for ryu to finish"
#until [ ! -f "__ryu_running" ]; do sleep 0.1; done
