# Copyright (C) 2011 Nippon Telegraph and Telephone Corporation.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
# implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import csv
from monotonic import monotonic
import os
import re
from ryu import cfg
from ryu.base import app_manager
from ryu.controller import ofp_event
from ryu.controller.handler import CONFIG_DISPATCHER, MAIN_DISPATCHER
from ryu.controller.handler import set_ev_cls
from ryu.ofproto import ofproto_v1_3
from ryu.lib import hub
from ryu.lib.packet import packet
from ryu.lib.packet import ethernet
from ryu.lib.packet import ipv4
from ryu.lib.packet import in_proto
from ryu.lib.packet import udp
from ryu.lib.packet import tcp
from ryu.lib.packet import ether_types
from ryu.topology import event, switches

class TestRouter13(app_manager.RyuApp):
    OFP_VERSIONS = [ofproto_v1_3.OFP_VERSION]

    def __init__(self, *args, **kwargs):
        super(TestRouter13, self).__init__(*args, **kwargs)
        self.mac_regex = re.compile("^02:00:00:00:([0-9A-Fa-f]{2}):([0-9A-Fa-f]{2})$")
        self.ip_regex = re.compile("^10\.0\.(\d{1,3})\.(\d{1,3})$")

        self.num_links = int(cfg.CONF.num_links)
        self.num_repetitions = int(cfg.CONF.num_repetitions)
        self.num_link = 1
        self.num_repetition = 0

        self.logger.info("%s %s", self.num_links, self.num_repetitions)

    def ip_to_eth(self, ip):
        result = self.dst_ip(ip)
        if result is None:
            return None
        return self.fmt_eth(result[0])

    def eth_to_port(self, eth):
        result = self.dst_eth(eth)
        if result is None:
            return None
        return result[0]

    def fmt_eth(self, iN = 0, jN = 0):
        return "02:00:00:00:{:02x}:{:02x}".format(iN, jN)

    def fmt_ip(self, iN = 0, jN = 0):
        return "10.0.{}.{}".format(iN, jN)

    def dst_eth(self, eth):
        result = self.mac_regex.match(eth)
        if result is None:
            return None
        return (int(result.group(1), 16), int(result.group(2), 16))

    def dst_ip(self, ip):
        result = self.ip_regex.match(ip)
        if result is None:
            return None
        return (int(result.group(1)), int(result.group(2)))

    def add_flow(self, datapath, priority, table_id, match, inst, **additional_args):
        ofproto = datapath.ofproto
        parser = datapath.ofproto_parser

        if all(isinstance(action, parser.OFPAction) for action in inst):
            inst = [parser.OFPInstructionActions(ofproto.OFPIT_APPLY_ACTIONS,
                                             inst)]

        mod = parser.OFPFlowMod(datapath=datapath, table_id=table_id, priority=priority,
                                match=match, instructions=inst,
                                **additional_args)
        datapath.send_msg(mod)

    def add_firewall_flow(self, datapath, drop, ipv4, **additional_args):
        ofproto = datapath.ofproto
        parser = datapath.ofproto_parser

        ipv4_ = pkt.get_protocol(ipv4.ipv4)
        if out_port != ofproto.OFPP_FLOOD and ipv4_ is not None:
            additional_match = {}
            if ipv4_.proto == in_proto.IPPROTO_TCP:
                tcp_ = pkt.get_protocol(tcp.tcp)
                additional_match['tcp_src'] = tcp_.src_port
                additional_match['tcp_dst'] = tcp_.dst_port
            elif ipv4_.proto == in_proto.IPPROTO_UDP:
                udp_ = pkt.get_protocol(udp.udp)
                additional_match['udp_src'] = udp_.src_port
                additional_match['udp_dst'] = udp_.dst_port

            match = parser.OFPMatch(
                in_port=in_port,
                eth_src=eth.src,
                eth_dst=eth.dst,
                eth_type=ether_types.ETH_TYPE_IP,
                ipv4_src=ipv4_.src,
                ipv4_dst=ipv4_.dst,
                ip_proto=ipv4_.proto,
                **additional_match
            )


        self.add_flow(datapath, 2, 0,
            parser.OFPMatch(
                eth_dst=self.fmt_eth(),
                eth_type=ether_types.ETH_TYPE_IP,
                ipv4_dst=(ip, '255.255.255.0')
            ),
            [parser.OFPInstructionGotoTable(1)] if drop else [],
            **additional_args
        )

    def add_routing_flow(self, datapath, ip, **additional_args):
        ofproto = datapath.ofproto
        parser = datapath.ofproto_parser

        self.add_flow(datapath, 2, 1,
            parser.OFPMatch(
                eth_dst=self.fmt_eth(),
                eth_type=ether_types.ETH_TYPE_IP,
                ipv4_dst=(ip, '255.255.255.0')
            ),
            [
                parser.OFPInstructionActions(ofproto.OFPIT_APPLY_ACTIONS, [
                    parser.OFPActionSetField(eth_src=self.fmt_eth()),
                    parser.OFPActionSetField(eth_dst=self.ip_to_eth(ip)),
                    parser.OFPActionDecNwTtl()
                ]),
                parser.OFPInstructionGotoTable(2)
            ],
            **additional_args
        )

    def add_switching_flow(self, datapath, eth, **additional_args):
        ofproto = datapath.ofproto
        parser = datapath.ofproto_parser

        self.add_flow(datapath, 2, 2,
            parser.OFPMatch(
                eth_dst=eth
            ),
            [
                parser.OFPActionOutput(self.eth_to_port(eth))
            ],
            **additional_args
        )

    def write_stat(self, stat, value):
        with open("sync/{}.csv".format(stat), 'a') as f:
            writer = csv.writer(f)
            writer.writerow(["tml" if cfg.CONF.with_tml else "no-tml", self.num_link, self.num_repetition, value])

    def next_experiment(self, datapath):
        self.num_repetition += 1
        if self.num_repetition > self.num_repetitions:
            self.num_link += 1
            self.num_repetition = 1
        if self.num_link > self.num_links:
            os.remove('__ryu_running')
            return
        if self.num_repetition == 1:
            self.logger.info("num_link: %s", self.num_link)
        
        ofproto = datapath.ofproto
        parser = datapath.ofproto_parser
        datapath.send_msg(parser.OFPFlowMod(
            datapath,
            table_id=ofproto.OFPTT_ALL,
            command=ofproto.OFPFC_DELETE,
            match=parser.OFPMatch()
        ))
        datapath.send_msg(parser.OFPBarrierRequest(datapath))
        self.execute = True

    def execute_experiment(self, datapath):
        self.barrier_time = monotonic()
        for i in range(self.num_link):
            iN = i + 1
            ip = self.fmt_ip(iN)
            self.add_routing_flow(datapath, ip)
            self.add_switching_flow(datapath, self.ip_to_eth(ip))
        datapath.send_msg(datapath.ofproto_parser.OFPBarrierRequest(datapath))
        self.execute = False

    @set_ev_cls(ofp_event.EventOFPSwitchFeatures, CONFIG_DISPATCHER)
    def switch_features_handler(self, ev):
        datapath = ev.msg.datapath
        ofproto = datapath.ofproto
        parser = datapath.ofproto_parser

        if datapath.id == 1:
            def delay():
                self.next_experiment(datapath)
            hub.spawn_after(20, delay)

    @set_ev_cls(ofp_event.EventOFPBarrierReply, MAIN_DISPATCHER)
    def _barrier_reply_handler(self, ev):
        if self.execute:
            self.execute_experiment(ev.msg.datapath)
        else:
            barrier_duration = monotonic() - self.barrier_time
            self.write_stat("barrier", barrier_duration)

            datapath = ev.msg.datapath
            parser = datapath.ofproto_parser
            self.stat_time = monotonic()
            datapath.send_msg(parser.OFPFlowStatsRequest(datapath))

    @set_ev_cls(ofp_event.EventOFPFlowStatsReply, MAIN_DISPATCHER)
    def _flow_stats_reply_handler(self, ev):
        stat_duration = monotonic() - self.stat_time
        self.write_stat("stat", stat_duration)
        self.next_experiment(ev.msg.datapath)
