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
"""
An OpenFlow 1.0 L2 learning switch implementation.
"""

import collections

from ryu.base import app_manager
from ryu.controller import ofp_event
from ryu.controller.handler import MAIN_DISPATCHER, set_ev_cls
from ryu.lib.packet import arp, ether_types, ethernet, icmp, ipv4, packet, tcp
from ryu.ofproto import inet, ofproto_v1_0, ofproto_v1_0_parser

IpPort = collections.namedtuple('IpPort', 'ip port')

ether_type_names = {
    ether_types.ETH_TYPE_IP: "IPv4",
    ether_types.ETH_TYPE_IPV6: "IPv6",
    ether_types.ETH_TYPE_LLDP: "LLDP",
    ether_types.ETH_TYPE_ARP: "ARP"
}


def ether_type_name(ethertype):
    if ethertype in ether_type_names:
        return ether_type_names[ethertype]
    return "UNKNOWN"


arp_opcode_names = {arp.ARP_REPLY: "Reply", arp.ARP_REQUEST: "Request"}


def arp_opcode_name(opcode):
    if opcode in arp_opcode_names:
        return arp_opcode_names[opcode]
    return "UNKNOWN"


ip_proto_names = {
    inet.IPPROTO_ICMP: "ICMP",
    inet.IPPROTO_ICMPV6: "ICMPv6",
    inet.IPPROTO_TCP: "TCP",
    inet.IPPROTO_UDP: "UDP"
}


def ip_proto_name(proto):
    if proto in ip_proto_names:
        return ip_proto_names[proto]
    return "UNKNOWN"


class SimpleSwitch(app_manager.RyuApp):
    OFP_VERSIONS = [ofproto_v1_0.OFP_VERSION]

    def __init__(self, *args, **kwargs):
        super(SimpleSwitch, self).__init__(*args, **kwargs)
        # { datapath_id: { mac_address: port } }
        self.mac_to_port = {}
        # { datapath_id: { ip_address: port } }
        self.ip_to_port = {}

    @set_ev_cls(ofp_event.EventOFPPacketIn, MAIN_DISPATCHER)
    def handle_packet_in(self, event: ofp_event.EventOFPPacketIn):
        packet_in = event.msg  # type: ofproto_v1_0_parser.OFPPacketIn
        datapath_id = packet_in.datapath.id

        frame = packet.Packet(packet_in.data)
        eth_header = frame.get_protocol(ethernet.ethernet)
        self.mac_to_port.setdefault(datapath_id,
                                    {})[eth_header.src] = packet_in.in_port

        eth_type = eth_header.ethertype
        self.logger.info(
            "received OFPT_PACKET_IN: buffer_id=0x%x total_len=%d in_port=%s",
            packet_in.buffer_id, packet_in.total_len, packet_in.in_port)
        self.logger.info("  %s -> %s, ethertype=0x%x (%s)", eth_header.src,
                         eth_header.dst, eth_type, ether_type_name(eth_type))
        if eth_type == ether_types.ETH_TYPE_ARP:
            self.handle_arp(packet_in, eth_header, frame.get_protocol(arp.arp))
        elif eth_type == ether_types.ETH_TYPE_IP:
            self.handle_ipv4(packet_in, frame, eth_header,
                             frame.get_protocol(ipv4.ipv4))

    def handle_arp(self,
                   packet_in: ofproto_v1_0_parser.OFPPacketIn,
                   eth_header: ethernet.ethernet,
                   arp_header: arp.arp):
        self.logger.info("  %s -> %s, opcode=0x%x (%s)", arp_header.src_ip,
                         arp_header.dst_ip, arp_header.opcode,
                         arp_opcode_name(arp_header.opcode))
        out_port = packet_in.datapath.ofproto.OFPP_FLOOD
        if arp_header.dst_mac in self.mac_to_port[packet_in.datapath.id]:
            out_port = self.mac_to_port[packet_in.datapath.id][
                arp_header.dst_mac]
        self.forward(packet_in, out_port)

    def forward(self, packet_in: ofproto_v1_0_parser.OFPPacketIn, port: int):
        data = None
        if packet_in.buffer_id == packet_in.datapath.ofproto.OFP_NO_BUFFER:
            data = packet_in.data
        packet_out = packet_in.datapath.ofproto_parser.OFPPacketOut(
            datapath=packet_in.datapath,
            buffer_id=packet_in.buffer_id,
            in_port=packet_in.in_port,
            data=data,
            actions=[packet_in.datapath.ofproto_parser.OFPActionOutput(port)])
        self.logger.info(
            "    sending packet_out: output packet on switch port %d", port)
        packet_in.datapath.send_msg(packet_out)

    def handle_ipv4(self,
                    packet_in: ofproto_v1_0_parser.OFPPacketIn,
                    frame: packet.Packet,
                    eth_header: ethernet.ethernet,
                    ipv4_header: ipv4.ipv4):
        self.logger.info("  %s -> %s, proto=0x%x (%s)", ipv4_header.src,
                         ipv4_header.dst, ipv4_header.proto,
                         ip_proto_name(ipv4_header.proto))
        datapath_id = packet_in.datapath.id
        self.ip_to_port.setdefault(datapath_id,
                                   {})[ipv4_header.src] = packet_in.in_port
        if ipv4_header.proto == inet.IPPROTO_TCP:
            tcp_header = frame.get_protocol(tcp.tcp)
            self.handle_tcp(packet_in, eth_header, ipv4_header, tcp_header)
        elif ipv4_header.proto == inet.IPPROTO_ICMP:
            icmp_header = frame.get_protocol(icmp.icmp)
            self.handle_icmp(packet_in, eth_header, ipv4_header, icmp_header)

    def handle_tcp(self,
                   packet_in: ofproto_v1_0_parser.OFPPacketIn,
                   eth_header: ethernet.ethernet,
                   ipv4_header: ipv4.ipv4,
                   tcp_header: tcp.tcp):
        self.logger.info("  %d -> %d", tcp_header.src_port,
                         tcp_header.dst_port)
        datapath = packet_in.datapath
        ofproto = datapath.ofproto
        out_port = ofproto.OFPP_FLOOD
        if ipv4_header.dst in self.ip_to_port[datapath.id]:
            out_port = self.ip_to_port[datapath.id][ipv4_header.dst]
            match = datapath.ofproto_parser.OFPMatch(
                dl_type=ether_types.ETH_TYPE_IP,  # doesn't work without this
                nw_proto=inet.IPPROTO_TCP,
                nw_dst=ipv4_header.dst,
                tp_dst=tcp_header.dst_port)
            mod = datapath.ofproto_parser.OFPFlowMod(
                datapath=datapath,
                match=match,
                command=ofproto.OFPFC_ADD,
                idle_timeout=0,
                hard_timeout=0,
                priority=ofproto.OFP_DEFAULT_PRIORITY,
                buffer_id=packet_in.buffer_id,
                actions=[datapath.ofproto_parser.OFPActionOutput(out_port)])
            datapath.send_msg(mod)
        self.forward(packet_in, out_port)

    def handle_icmp(self,
                    packet_in: ofproto_v1_0_parser.OFPPacketIn,
                    eth_header: ethernet.ethernet,
                    ipv4_header: ipv4.ipv4,
                    icmp_header: icmp.icmp):
        out_port = packet_in.datapath.ofproto.OFPP_FLOOD
        datapath_id = packet_in.datapath.id
        if ipv4_header.dst in self.ip_to_port[datapath_id]:
            out_port = self.ip_to_port[datapath_id][ipv4_header.dst]
        self.forward(packet_in, out_port)

    @set_ev_cls(ofp_event.EventOFPPortStatus, MAIN_DISPATCHER)
    def _port_status_handler(self, ev):
        msg = ev.msg
        reason = msg.reason
        port_no = msg.desc.port_no

        ofproto = msg.datapath.ofproto
        if reason == ofproto.OFPPR_ADD:
            self.logger.info("port added %s", port_no)
        elif reason == ofproto.OFPPR_DELETE:
            self.logger.info("port deleted %s", port_no)
        elif reason == ofproto.OFPPR_MODIFY:
            self.logger.info("port modified %s", port_no)
        else:
            self.logger.info("Illeagal port state %s %s", port_no, reason)
