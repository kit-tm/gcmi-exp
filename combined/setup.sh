virtualenv -p python2 .

source bin/activate
pushd ryu
pip install .
popd

pushd mininet
vagrant up
popd