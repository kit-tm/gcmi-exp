#! /bin/bash
set -e

function delete {
	until [ $(curl -w '%{http_code}' --user onos:rocks -X "DELETE" localhost:8181/onos/v1/applications/$1/active) != "404" ]; do sleep 1; done
}

until nc -z localhost 8181; do sleep 0.1; done; sleep 5

sleep 10

delete org.onosproject.proxyarp
delete org.onosproject.fwd
delete org.onosproject.mobility
pushd onos/apps/learning-switch
onos-app localhost reinstall! target/onos-apps-learning-switch-1.14.0-SNAPSHOT.jar
popd
