#! /bin/bash
set -e

scripts/test.sh test_1 16 1 10000
scripts/test.sh test_2 16 1 600
scripts/test.sh test_3 16 1 400
scripts/test.sh test_4 16 1 200

pushd sync
python ../scripts/plot.py test_1 - test_2 600 test_3 400 test_4 200
popd
