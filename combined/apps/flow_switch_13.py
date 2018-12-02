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
import random
import re
from ryu import cfg
from ryu.base import app_manager
from ryu.controller import ofp_event
from ryu.controller.handler import CONFIG_DISPATCHER, MAIN_DISPATCHER
from ryu.controller.handler import set_ev_cls
from ryu.ofproto import ofproto_v1_3
from ryu.lib.packet import packet
from ryu.lib.packet import ethernet
from ryu.lib.packet import ipv4
from ryu.lib.packet import in_proto
from ryu.lib.packet import udp
from ryu.lib.packet import tcp
from ryu.lib.packet import ether_types
from ryu.topology import event, switches

class SimpleFlowSwitch13(app_manager.RyuApp):
    OFP_VERSIONS = [ofproto_v1_3.OFP_VERSION]

    def __init__(self, *args, **kwargs):
        super(SimpleFlowSwitch13, self).__init__(*args, **kwargs)
        self.mac_regex = re.compile("^02:00:00:00:([0-9A-Fa-f]{2}):([0-9A-Fa-f]{2})$")
        self.ip_regex = re.compile("^10\.0\.(\d{1,3})\.(\d{1,3})$")

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

    def add_firewall_flow(self, datapath, drop, in_port, pkt, eth_, ipv4_, **additional_args):
        ofproto = datapath.ofproto
        parser = datapath.ofproto_parser

        additional_match = {}
        if ipv4_.proto == in_proto.IPPROTO_TCP:
            tcp_ = pkt.get_protocol(tcp.tcp)
            additional_match['tcp_src'] = tcp_.src_port
            additional_match['tcp_dst'] = tcp_.dst_port
        elif ipv4_.proto == in_proto.IPPROTO_UDP:
            udp_ = pkt.get_protocol(udp.udp)
            additional_match['udp_src'] = udp_.src_port
            additional_match['udp_dst'] = udp_.dst_port

        self.add_flow(datapath, 2, 0,
            parser.OFPMatch(
                in_port=in_port,
                eth_src=eth_.src,
                eth_dst=eth_.dst,
                eth_type=ether_types.ETH_TYPE_IP,
                ipv4_src=ipv4_.src,
                ipv4_dst=ipv4_.dst,
                ip_proto=ipv4_.proto,
                **additional_match
            ),
            [] if drop else [parser.OFPInstructionGotoTable(1)],
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

    def packet_out(self, datapath, drop, in_port, ip, data):
        ofproto = datapath.ofproto
        parser = datapath.ofproto_parser

        if drop:
            return

        eth=self.ip_to_eth(ip)
        port=self.eth_to_port(eth)

        datapath.send_msg(parser.OFPPacketOut(
            datapath=datapath, in_port=in_port,
            buffer_id=ofproto.OFP_NO_BUFFER,
            actions=[
                parser.OFPActionSetField(eth_src=self.fmt_eth()),
                parser.OFPActionSetField(eth_dst=eth),
                parser.OFPActionDecNwTtl(),
                parser.OFPActionOutput(port)
            ],
            data=data
        ))

    @set_ev_cls(ofp_event.EventOFPSwitchFeatures, CONFIG_DISPATCHER)
    def switch_features_handler(self, ev):
        datapath = ev.msg.datapath
        ofproto = datapath.ofproto
        parser = datapath.ofproto_parser

        self.num_links = int(cfg.CONF.num_links)
        self.num_hosts_per_link = 1

        if datapath.id == 1:
            self.add_flow(datapath, 1, 0,
                parser.OFPMatch(),
                [parser.OFPActionOutput(
                    ofproto.OFPP_CONTROLLER,
                    ofproto.OFPCML_NO_BUFFER
                )]
            )

            for i in range(self.num_links):
                iN = i + 1
                ip = self.fmt_ip(iN)
                self.add_routing_flow(datapath, ip)
                self.add_switching_flow(datapath, self.ip_to_eth(ip))

        elif datapath.id == 4:
            self.add_flow(datapath, 1, 0, parser.OFPMatch(), [])
            for i in range(self.num_links):
                iN = i + 1
                for j in range(self.num_hosts_per_link):
                    jN = j + 1
                    self.add_flow(datapath, 2, 0,
                        parser.OFPMatch(
                            in_port=iN,
                            eth_src=self.fmt_eth(),
                            eth_dst=self.fmt_eth(iN),
                            eth_type=ether_types.ETH_TYPE_IP,
                            ipv4_dst=self.fmt_ip(iN, jN)
                        ),
                        [
                            parser.OFPActionSetField(eth_src=self.fmt_eth(iN)),
                            parser.OFPActionSetField(eth_dst=self.fmt_eth(iN, jN)),
                            parser.OFPActionDecNwTtl(),
                            parser.OFPActionOutput(
                                self.num_links + i * self.num_hosts_per_link + jN)
                        ]
                    )
                    self.add_flow(datapath, 2, 0,
                        parser.OFPMatch(
                            in_port=self.num_links + i * self.num_hosts_per_link + jN,
                            eth_src=self.fmt_eth(iN, jN),
                            eth_dst=self.fmt_eth(iN),
                            eth_type=ether_types.ETH_TYPE_IP
                        ),
                        [
                            parser.OFPActionSetField(eth_src=self.fmt_eth(iN)),
                            parser.OFPActionSetField(eth_dst=self.fmt_eth()),
                            parser.OFPActionDecNwTtl(),
                            parser.OFPActionOutput(iN)
                        ]
                    )

    @set_ev_cls(ofp_event.EventOFPPacketIn, MAIN_DISPATCHER)
    def _packet_in_handler(self, ev):
        # If you hit this you might want to increase
        # the "miss_send_length" of your switch
        if ev.msg.msg_len < ev.msg.total_len:
            self.logger.debug("packet truncated: only %s of %s bytes",
                              ev.msg.msg_len, ev.msg.total_len)
        msg = ev.msg
        datapath = msg.datapath
        dpid = datapath.id
        ofproto = datapath.ofproto
        parser = datapath.ofproto_parser
        in_port = msg.match['in_port']

        pkt = packet.Packet(msg.data)
        eth_ = pkt.get_protocol(ethernet.ethernet)
        ipv4_ = pkt.get_protocol(ipv4.ipv4)
        if ipv4_ is None:
            self.logger.info("packet in %s %s %s %s", dpid, in_port, eth_.src, eth_.dst)
            # ignore everything except ip packets
            return

        self.logger.info("packet in %s %s %s %s %s %s", dpid, in_port, eth_.src, eth_.dst, ipv4_.src, ipv4_.dst)

        drop = random.random() > 1.0
        self.add_firewall_flow(datapath, drop, in_port, pkt, eth_, ipv4_, idle_timeout=5)
        self.packet_out(datapath, drop, in_port, ipv4_.dst, msg.data)
