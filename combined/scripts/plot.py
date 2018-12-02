#!/usr/bin/env python

import csv
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import matplotlib.gridspec as gridspec
import matplotlib.ticker as plticker
from matplotlib.backends.backend_pdf import PdfPages
import numpy as np
import sys
FONTSIZE_OUTER = 14

def read_csv(path):
    with open(path, 'rb') as csv_file:
        csv_reader = csv.reader(csv_file)
        rows = [row for row in csv_reader]
        return ([float(row[0]) for row in rows], [int(row[1]) for row in rows])

def main():
    fig = plt.figure(figsize=(((len(sys.argv) - 1)/2) * 15/4, 2.5), dpi=300)
    pp = PdfPages("plot.pdf")
    gs = gridspec.GridSpec(1, (len(sys.argv) - 1)/2)
    gs.update(wspace=0.225, hspace=0.35)

    for i, (test, thres) in enumerate(zip(sys.argv[1::2], sys.argv[2::2])):
        rs_x, rs_y = read_csv('{}_rs.csv'.format(test))
        t1_x, t1_y = read_csv('{}_t1.csv'.format(test))
        t2_x, t2_y = read_csv('{}_t2.csv'.format(test))
        tr = (np.average(t1_y) + np.average(t2_y))/np.average(rs_y)

        p1 = plt.subplot(gs[i])
        p1.set_xlabel('Time (s)', fontsize=FONTSIZE_OUTER+2)
        if i == 0:
            p1.set_ylabel(
                'Flow Table Utilization', labelpad=15,fontsize=FONTSIZE_OUTER+2)
        p1.text(240, 770, "TR={0:.2f}".format(tr), color='black', fontsize=14)
        p1.tick_params(axis="both", direction="inout")
        p1.set_xticks((0,100,200,300,400))
        p1.set_yticks((0,100,200,300,400,500,600,700,800,900))
        p1.set_xlim(0, 400)
        p1.set_ylim(0, 900)

        pl_rs = p1.plot(rs_x, rs_y, c="#1b9e77", linewidth=1)
        pl_t1 = p1.plot(t1_x, t1_y, c="#7570b3", linewidth=1, dashes=[1,1])
        pl_t2 = p1.plot(t2_x, t2_y, c="#66a61e", linewidth=1, dashes=[1,1])
        if thres != '-':
            th = p1.axhline(int(thres), c="black", linestyle="--", linewidth=1)

    p1.legend(
        [pl_rs[0], pl_t1[0], pl_t2[0], th],
        ['Table 0', 'Table 1', 'Table 2', 'Delegation Threshold'],
        ncol=4, bbox_to_anchor=(-3.125, 1.02, 1., .102), loc=3,
        frameon=False, fancybox=True, shadow=True, prop={'size':FONTSIZE_OUTER}
    )

    pp.savefig(bbox_inches='tight')
    pp.close()

if __name__ == "__main__":
    main()
