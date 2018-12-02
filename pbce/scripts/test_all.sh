#! /bin/bash
set -e

scripts/test.sh test_1
scripts/test.sh test_2
scripts/test.sh test_3
scripts/test.sh test_4

pushd sync
python ../scripts/plot.py test_1 - test_2 600 test_3 400 test_4 200
popd
