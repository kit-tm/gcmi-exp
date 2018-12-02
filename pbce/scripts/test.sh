#! /bin/bash
set -e
function cleanup {
    echo "killing all background jobs"
    pkill --full java
    pkill --full python
    sudo mn -c
}
trap cleanup EXIT

OUTD=$PWD/sync

echo "clearing potential output files"
rm -f __mn_running __mn_slow $OUTD/$1_*.log $OUTD/{ts,ds,es}.csv $OUTD/$1_*.csv

echo "starting transparent middle layer"
pushd tml
./gradlew run &> $OUTD/$1_tml.log &
until nc -z localhost 8080; do sleep 0.1; done
popd

echo "starting ryu"
# TODO make app configurable (low priority)
ryu-manager --observe-links apps/flow_switch_13.py &> $OUTD/$1_ryu.log &
until nc -z localhost 6653; do sleep 0.1; done

echo "configuring transparent middle layer"
curl -f -H "Content-Type: application/json" -X POST -d "$(< configs/$1.json)" \
localhost:8080/layers

curl -f -H "Content-Type: application/json" -X POST -d '
{
	"source": { "layer" : 1 },
	"targets": [ { "address" : "127.0.0.1:6653" } ]
}
' localhost:8080/edges

echo "starting mininet"
touch __mn_running
sudo python scripts/test.py &> $OUTD/$1_mininet.log &

echo "waiting for mininet to finish"
until [ ! -f "__mn_running" ]; do sleep 0.1; done

if [ -f "__mn_slow" ]; then
    echo "mininet aborted due to slow execution"
    false
fi

for f in $OUTD/{ts,ds,es}.csv; do mv -- "$f" "$OUTD/$1_$(basename $f)"; done
