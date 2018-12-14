#!/usr/bin/env python2.7

import csv
import re
import sys

regex = "^MEASUREMENT: cnt\/min\/max\/avg\/dev = ([0-9\.]*)\/([0-9\.]*)\/([0-9\.]*)\/([0-9\.]*)\/([0-9\.]*)$"

with open(sys.argv[1]) as i:
    lines = i.read()

match = re.search(regex, lines, re.MULTILINE)
res = [match.group(1), match.group(2), match.group(3), match.group(4), match.group(5)]

with open(sys.argv[2], 'ab') as o:
    writer = csv.writer(o)
    writer.writerow(sys.argv[3:] + res)
