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

import re
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
        self.regex = re.compile("^02:00:00:00:([0-9A-Fa-f]{2}):[0-9A-Fa-f]{2}$")

    def mac_to_port(self, mac):
        result = self.regex.match(mac)
        if result is None:
            return None
        return int(result.group(1), 16) + 1

    @set_ev_cls(ofp_event.EventOFPSwitchFeatures, CONFIG_DISPATCHER)
    def switch_features_handler(self, ev):
        datapath = ev.msg.datapath
        ofproto = datapath.ofproto
        parser = datapath.ofproto_parser

        num_links = 16
        num_hosts_per_link = 1

        if (datapath.id == 2):
            self.add_flow(datapath, 1, parser.OFPMatch(), [])
            for i in range(num_links):
                self.add_flow(datapath, 2,
                    parser.OFPMatch(
                        in_port=i + 1
                    ),
                    [parser.OFPActionOutput(
                        num_links + i * num_hosts_per_link + j + 1
                    ) for j in range(num_hosts_per_link)]
                )
                for j in range(num_hosts_per_link):
                    self.add_flow(datapath, 2,
                        parser.OFPMatch(
                            in_port=num_links + i * num_hosts_per_link + j + 1
                        ),
                        [parser.OFPActionOutput(i + 1)]
                    )
                    self.add_flow(datapath, 3,
                        parser.OFPMatch(
                            in_port=i + 1,
                            eth_dst="02:00:00:00:{:02x}:{:02x}".format(i, j)
                        ),
                        [parser.OFPActionOutput(
                            num_links + i * num_hosts_per_link + j + 1
                        )]
                    )
        else:
            self.add_flow(datapath, 1,
                parser.OFPMatch(),
                [parser.OFPActionOutput(
                    ofproto.OFPP_CONTROLLER,
                    ofproto.OFPCML_NO_BUFFER
                )]
            )

    def add_flow(self, datapath, priority, match, actions, **additional_args):
        ofproto = datapath.ofproto
        parser = datapath.ofproto_parser

        inst = [parser.OFPInstructionActions(ofproto.OFPIT_APPLY_ACTIONS,
                                             actions)]
        mod = parser.OFPFlowMod(datapath=datapath, priority=priority,
                                match=match, instructions=inst,
                                **additional_args)
        datapath.send_msg(mod)

    @set_ev_cls(event.EventLinkAdd)
    def _event_link_add_handler(self, ev):
        pass

    @set_ev_cls(ofp_event.EventOFPPacketIn, MAIN_DISPATCHER)
    def _packet_in_handler(self, ev):
        # If you hit this you might want to increase
        # the "miss_send_length" of your switch
        if ev.msg.msg_len < ev.msg.total_len:
            self.logger.debug("packet truncated: only %s of %s bytes",
                              ev.msg.msg_len, ev.msg.total_len)
        msg = ev.msg
        datapath = msg.datapath
        ofproto = datapath.ofproto
        parser = datapath.ofproto_parser
        in_port = msg.match['in_port']

        pkt = packet.Packet(msg.data)
        eth = pkt.get_protocols(ethernet.ethernet)[0]

        if eth.ethertype == ether_types.ETH_TYPE_LLDP:
            # ignore lldp packet
            return

        dpid = datapath.id
        self.logger.info("packet in %s %s %s %s", dpid, eth.src, eth.dst, in_port)

        out_port = self.mac_to_port(eth.dst)
        if out_port is None:
            out_port = ofproto.OFPP_FLOOD

        actions = [parser.OFPActionOutput(
            ofproto.OFPP_IN_PORT if in_port == out_port else out_port
        )]

        # install a flow to avoid packet_in next time
        if (out_port != ofproto.OFPP_FLOOD and
            eth.ethertype == ether_types.ETH_TYPE_IP):
            ipv4_ = pkt.get_protocol(ipv4.ipv4)

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

            # verify if we have a valid buffer_id, if yes avoid to send both
            # flow_mod & packet_out
            if msg.buffer_id != ofproto.OFP_NO_BUFFER:
                self.add_flow(datapath, 3, match, actions, idle_timeout=5, buffer_id=msg.buffer_id)
                return
            else:
                self.add_flow(datapath, 3, match, actions, idle_timeout=5)
        data = None
        if msg.buffer_id == ofproto.OFP_NO_BUFFER:
            data = msg.data

        out = parser.OFPPacketOut(datapath=datapath, buffer_id=msg.buffer_id,
                                  in_port=in_port, actions=actions, data=data)
        datapath.send_msg(out)
