#!/usr/bin/env python

import csv
import math
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.backends.backend_pdf import PdfPages
import numpy as np
import sys

def read_csv(path):
    with open(path, 'rb') as csv_file:
        csv_reader = csv.reader(csv_file)
        return [row for row in csv_reader]

def transpose(rows):
    c = len(rows[0]) if rows else 0
    return [[row[i] for row in rows] for i in range(c)]

def format_num_layers(num_layers):
    if num_layers == -1:
        return "direct"
    else:
        return str(int(num_layers))

def main2():
    rows = read_csv(sys.argv[1])
    cols = transpose(rows)

    cols[-5] = [int(val) for val in cols[-5]]
    cols[-2] = [1000 * float(val) for val in cols[-2]]
    cols[-1] = [1000 * float(val) for val in cols[-1]]

    x_col = int(sys.argv[3])
    if x_col == 2:
        cols[x_col] = [format_num_layers(val) for val in cols[x_col]]

    fig = plt.figure()
    pp = PdfPages(sys.argv[2])

    y_flr = int(math.floor(min([m - s for m, s in zip(cols[-2], cols[-1])])))
    y_cel = int(math.ceil(max([m + s for m, s in zip(cols[-2], cols[-1])])))

    plt.xlim(-0.5, len(cols[-1]) - 0.5)
    plt.ylim(y_flr - 0.5, y_cel + 0.5)
    plt.xticks(np.arange(len(cols[-1])), cols[x_col])
    plt.yticks(np.arange(y_flr, y_cel + 1), np.arange(y_flr, y_cel + 1))
    plt.ylabel("Control Plane Latency (ms)")
    for y in range(y_flr, y_cel + 1):
        plt.axhline(y, -0.5, len(cols[-1]) + 0.5, alpha=0.25, c='gray', lw=0.25, zorder=1.0)
    plt.errorbar(np.arange(len(cols[-1])), cols[-2], cols[-1], fmt='ok', capsize=4, capthick=1, elinewidth=1, ecolor="#FF9933", mec="#336699", mfc="#336699", ms=4)
    plt.plot(np.arange(len(cols[-1])), cols[-2], color="#336699", ls='solid', lw=1)

    if x_col == 2:
        plt.xlabel("Intermediate Layers")

    pp.savefig(bbox_inches='tight')
    pp.close()

def read(csv_path):
    cols = transpose(read_csv(csv_path))
    cols[2] = [float(val) for val in cols[2]]
    cols[-5] = [float(val) for val in cols[-5]]
    cols[-2] = [1000 * float(val) for val in cols[-2]]
    cols[-1] = [1000 * float(val) for val in cols[-1]]
    return cols

def limits(x_col, cols):
    y_min = int(math.floor(min([m - s for m, s in zip(cols[-2], cols[-1])])))
    y_max = int(math.ceil(max([m + s for m, s in zip(cols[-2], cols[-1])])))

    return (set(cols[x_col]), y_min, y_max)

def plot(plt, cols, off, color, ecolor):
    o_col = [val + off for val in np.arange(len(cols[0]))]
    me = plt.errorbar(o_col, cols[-2], cols[-1], fmt='ok', capsize=4, capthick=1, elinewidth=1, ecolor=ecolor, mec=color, mfc=color, ms=4)
    av = plt.plot(o_col, cols[-2], color=color, ls='solid', lw=1)
    return me, av

def main():
    fig = plt.figure()
    pp = PdfPages(sys.argv[1])
    x_col = int(sys.argv[2])

    if x_col == 2:
        plt.xlabel("Intermediate Layers")
    plt.ylabel("Control Plane Latency (ms)")

    names = [name for name in sys.argv[3::3]]
    colss = [read(csv_path) for csv_path in sys.argv[4::3]]
    offs = [float(off) for off in sys.argv[5::3]]

    xs = set()
    y_min, y_max = float('inf'), float('-inf')
    for cols in colss:
        _xs, _y_min, _y_max = limits(x_col, cols)
        xs.update(_xs)
        y_min = min(y_min, _y_min)
        y_max = max(y_max, _y_max)

    plt.xlim(- 0.5, len(xs) - 0.5)
    plt.ylim(y_min - 0.5, y_max + 0.5)
    if x_col == 2:
        plt.xticks(np.arange(len(xs)), [format_num_layers(val) for val in sorted(xs)])
    plt.yticks(np.arange(y_min, y_max + 1), np.arange(y_min, y_max + 1))
    for y in range(y_min, y_max + 1):
        plt.axhline(y, -0.5, len(xs) - 0.5, alpha=0.25, c='gray', lw=0.25, zorder=1.0)

    plots = []
    colors = ["#1b9e77", "#7570b3", "#66a61e"]
    ecolors = ["#d95f02", "#e7298a", "#a6761d"]

    for cols, off, color, ecolor in zip(colss, offs, colors, ecolors):
        plots.append(plot(plt, cols, off, color, ecolor))
    mes, _ = transpose(plots)

    plt.legend(mes, names)

    pp.savefig(bbox_inches='tight')
    pp.close()

if __name__ == "__main__":
    main()
