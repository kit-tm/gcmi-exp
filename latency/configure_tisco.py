#!/usr/bin/env python2.7

import requests
import sys

layer_type = sys.argv[1]
num_layers = int(sys.argv[2])
listen_address = sys.argv[3] + ":6653"
upstream_address = sys.argv[4] + ":6653"

for i in range(num_layers):
    resp = requests.post(
        'http://localhost:8080/layers', json={"type": layer_type})
    assert resp.ok

resp = requests.post(
    'http://localhost:8080/edges',
    json={
        "source": { "layer": num_layers } if num_layers > 0 else { "address": listen_address },
        "targets": [{ "address": upstream_address }]})
assert resp.ok

for i in range(num_layers, 1, -1):
    resp = requests.post(
        'http://localhost:8080/edges',
        json={
            "source": { "layer": i - 1 },
            "targets": [{ "layer": i }]})
    assert resp.ok

if num_layers > 0:
    resp = requests.post(
        'http://localhost:8080/edges',
        json={
            "source": { "address": listen_address },
            "targets": [{ "layer": 1 }]})
    assert resp.ok
