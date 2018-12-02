package com.github.sherter.jcon.composer;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.sherter.jcon.InterceptableMultiForwarder;
import com.github.sherter.jcon.networking.Reactor;
import java.io.IOException;
import java.lang.Iterable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.Selector;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.projectfloodlight.openflow.protocol.*;
import org.projectfloodlight.openflow.protocol.errormsg.*;
import org.projectfloodlight.openflow.protocol.instruction.*;
import org.projectfloodlight.openflow.protocol.action.*;
import org.projectfloodlight.openflow.protocol.match.*;
import org.projectfloodlight.openflow.types.*;
import com.google.common.collect.Streams;
import lombok.Data;
import lombok.AllArgsConstructor;

public class TableVisorLayer implements Layer {
    private static final Logger log = LoggerFactory.getLogger(Pbce2Layer.class);
    private static final OFFactory factory = OFFactories.getFactory(OFVersion.OF_13);
    private static final OFPort tablePortOut = OFPort.of(31);
    private static final OFPort tablePortIn = OFPort.of(30);

    private static class TVContext {
        public final DatapathId dpid;
        public final InterceptableMultiForwarder.Context controller;
        public final List<InterceptableMultiForwarder.Context> switches;

        public final Set<Integer> ports = new HashSet<Integer>();
        public final Map<Long, Integer> refs = new HashMap<Long, Integer>();
        public final Map<Long, List<OFFlowStatsEntry>> entries = new HashMap<Long, List<OFFlowStatsEntry>>();

        public TVContext(DatapathId dpid, InterceptableMultiForwarder.Context controller, List<InterceptableMultiForwarder.Context> switches) {
            this.dpid = dpid;
            this.controller = controller;
            this.switches = switches;
        }
    }

    @Data
    @AllArgsConstructor(staticName = "of")
    private static class Pair<A, B> {
        private A first;
        private B second;
    }
    
    private final LayerService service;
    private final Reactor reactor;
    private final RoutingProxy proxy;
    private final InterceptableMultiForwarder forwarder;
    private final InetSocketAddress listenAddress;
    private final Thread reactorThread;

    private final Map<DatapathId, List<DatapathId>> config;

    private final Map<InterceptableMultiForwarder.Context, TVContext> handlerContexts = new HashMap<>();
    private final Map<DatapathId, InterceptableMultiForwarder.Context> unassignedSwitches = new HashMap<>();
  
    public static <T> Collector<T, ?, Optional<T>> singletonCollector() {
        return Collectors.collectingAndThen(
                Collectors.toList(),
                list -> {
                    if (list.size() == 0) {
                        return Optional.empty();
                    } else if (list.size() == 1) {
                        return Optional.of(list.get(0));
                    } else {
                        throw new IllegalStateException();
                    }
                }
        );
    }

    private static <T extends OFValueType<T>> void setField(MatchField<T> matchField, OFMatchV3 match, OFMatchV3.Builder newMatch) {
        if (!match.supports(matchField) || match.isFullyWildcarded(matchField)) {
            return;
        }
        if (match.isExact(matchField)) {
            newMatch.setExact(matchField, match.get(matchField));
        } else {
            newMatch.setMasked(matchField, match.getMasked(matchField));
        }
    }

    private static OFMatchV3.Builder matchToBuilder(OFMatchV3 match) {
        OFMatchV3.Builder newMatch = factory.buildMatchV3();
        setField(MatchField.PACKET_TYPE, match, newMatch);
        setField(MatchField.IN_PORT, match, newMatch);
        setField(MatchField.IN_PHY_PORT, match, newMatch);
        setField(MatchField.METADATA, match, newMatch);
        setField(MatchField.ETH_DST, match, newMatch);
        setField(MatchField.ETH_SRC, match, newMatch);
        setField(MatchField.ETH_TYPE, match, newMatch);
        setField(MatchField.VLAN_VID, match, newMatch);
        setField(MatchField.VLAN_PCP, match, newMatch);
        setField(MatchField.IP_DSCP, match, newMatch);
        setField(MatchField.IP_ECN, match, newMatch);
        setField(MatchField.IP_PROTO, match, newMatch);
        setField(MatchField.IPV4_SRC, match, newMatch);
        setField(MatchField.IPV4_DST, match, newMatch);
        setField(MatchField.TCP_SRC, match, newMatch);
        setField(MatchField.TCP_DST, match, newMatch);
        setField(MatchField.UDP_SRC, match, newMatch);
        setField(MatchField.UDP_DST, match, newMatch);
        setField(MatchField.SCTP_SRC, match, newMatch);
        setField(MatchField.SCTP_DST, match, newMatch);
        setField(MatchField.ICMPV4_TYPE, match, newMatch);
        setField(MatchField.ICMPV4_CODE, match, newMatch);
        setField(MatchField.ARP_OP, match, newMatch);
        setField(MatchField.ARP_SPA, match, newMatch);
        setField(MatchField.ARP_TPA, match, newMatch);
        setField(MatchField.ARP_SHA, match, newMatch);
        setField(MatchField.ARP_THA, match, newMatch);
        setField(MatchField.IPV6_SRC, match, newMatch);
        setField(MatchField.IPV6_DST, match, newMatch);
        setField(MatchField.IPV6_FLABEL, match, newMatch);
        setField(MatchField.ICMPV6_TYPE, match, newMatch);
        setField(MatchField.ICMPV6_CODE, match, newMatch);
        setField(MatchField.IPV6_ND_TARGET, match, newMatch);
        setField(MatchField.IPV6_ND_SLL, match, newMatch);
        setField(MatchField.IPV6_ND_TLL, match, newMatch);
        setField(MatchField.MPLS_LABEL, match, newMatch);
        setField(MatchField.MPLS_TC, match, newMatch);
        setField(MatchField.MPLS_BOS, match, newMatch);
        setField(MatchField.TUNNEL_ID, match, newMatch);
        setField(MatchField.IPV6_EXTHDR, match, newMatch);
        setField(MatchField.PBB_UCA, match, newMatch);
        setField(MatchField.TCP_FLAGS, match, newMatch);
        setField(MatchField.OVS_TCP_FLAGS, match, newMatch);
        setField(MatchField.ACTSET_OUTPUT, match, newMatch);
        setField(MatchField.TUNNEL_IPV4_SRC, match, newMatch);
        setField(MatchField.TUNNEL_IPV4_DST, match, newMatch);
        setField(MatchField.BSN_IN_PORTS_128, match, newMatch);
        setField(MatchField.BSN_IN_PORTS_512, match, newMatch);
        setField(MatchField.BSN_LAG_ID, match, newMatch);
        setField(MatchField.BSN_VRF, match, newMatch);
        setField(MatchField.BSN_GLOBAL_VRF_ALLOWED, match, newMatch);
        setField(MatchField.BSN_L3_INTERFACE_CLASS_ID, match, newMatch);
        setField(MatchField.BSN_L3_SRC_CLASS_ID, match, newMatch);
        setField(MatchField.BSN_L3_DST_CLASS_ID, match, newMatch);
        setField(MatchField.BSN_EGR_PORT_GROUP_ID, match, newMatch);
        setField(MatchField.BSN_UDF0, match, newMatch);
        setField(MatchField.BSN_UDF1, match, newMatch);
        setField(MatchField.BSN_UDF2, match, newMatch);
        setField(MatchField.BSN_UDF3, match, newMatch);
        setField(MatchField.BSN_UDF4, match, newMatch);
        setField(MatchField.BSN_UDF5, match, newMatch);
        setField(MatchField.BSN_UDF6, match, newMatch);
        setField(MatchField.BSN_UDF7, match, newMatch);
        setField(MatchField.BSN_TCP_FLAGS, match, newMatch);
        setField(MatchField.BSN_VLAN_XLATE_PORT_GROUP_ID, match, newMatch);
        setField(MatchField.BSN_L2_CACHE_HIT, match, newMatch);
        setField(MatchField.BSN_INGRESS_PORT_GROUP_ID, match, newMatch);
        setField(MatchField.BSN_VXLAN_NETWORK_ID, match, newMatch);
        setField(MatchField.BSN_INNER_ETH_DST, match, newMatch);
        setField(MatchField.BSN_INNER_ETH_SRC, match, newMatch);
        setField(MatchField.BSN_INNER_VLAN_VID, match, newMatch);
        setField(MatchField.BSN_VFI, match, newMatch);
        setField(MatchField.BSN_IP_FRAGMENTATION, match, newMatch);
        return newMatch;
    }

    private static OFMessage backflowRule(OFPort outport) {
        return factory.buildFlowAdd()
            .setOutPort(OFPort.ANY)
            .setOutGroup(OFGroup.ANY)
            .setPriority(2)
            .setMatch(factory.buildMatch()
                .setExact(MatchField.IN_PORT, tablePortIn)
                //.setExact(MatchField.ETH_TYPE, EthType.VLAN_FRAME)
                .setExact(MatchField.VLAN_VID, OFVlanVidMatch.ofVlan(outport.getPortNumber()))
                .build()
            )
            .setInstructions(Arrays.asList(
                factory.instructions().buildApplyActions()
                    .setActions(Arrays.asList(
                        factory.actions().popVlan(),
                        factory.actions().buildOutput()
                            .setPort(outport)
                            .build()
                    ))
                    .build()
            ))
            .build();
    }

    public TableVisorLayer(LayerService service, JsonNode config) throws IOException {
        this.service = service;
        reactor = new Reactor(Selector.open());
        proxy = RoutingProxy.listenOn(reactor, null);
        forwarder = new InterceptableMultiForwarder.Builder(reactor, proxy.listenAddress(), this::switch_connect, this::controller_connect)
            .interceptUpstream(OFHello.class, this::switch_hello)
            .interceptUpstream(OFEchoRequest.class, this::echo_request)
            .interceptUpstream(OFFeaturesReply.class, this::switch_features_reply)
            .interceptUpstream(OFPortStatus.class, this::switch_port_status)
            .interceptUpstream(OFStatsReply.class, this::switch_stats_reply)
            .interceptUpstream(OFPacketIn.class, this::switch_packet_in)
            .interceptUpstream(OFBarrierReply.class, this::switch_barrier_reply)
            .interceptUpstream(OFErrorMsg.class, this::error)
            .interceptDownstream(OFHello.class, this::controller_hello)
            .interceptDownstream(OFEchoRequest.class, this::echo_request)
            .interceptDownstream(OFFeaturesRequest.class, this::controller_features_request)
            .interceptDownstream(OFStatsRequest.class, this::controller_stats_request)
            .interceptDownstream(OFFlowMod.class, this::controller_flow_mod)
            .interceptDownstream(OFPacketOut.class, this::controller_packet_out)
            .interceptDownstream(OFBarrierRequest.class, this::controller_barrier_request)
            .interceptDownstream(OFErrorMsg.class, this::error)
            .build();
        listenAddress = forwarder.listenOn(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        reactorThread = new Thread(() -> reactor.loop());

        this.config = Streams.stream(config.fields())
            .collect(Collectors.toMap(
                entry -> DatapathId.of(entry.getKey()),
                entry -> Streams.stream(entry.getValue())
                    .map(node -> DatapathId.of(node.asText()))
                    .collect(Collectors.toList())));

        reactorThread.start();
    }

    public void updatePortBackflowRule(TVContext tContext, OFPort port) {
        if (Integer.toUnsignedLong(port.getPortNumber()) >= Integer.toUnsignedLong(tablePortIn.getPortNumber()))
            return;
        int portNumber = port.getPortNumber();
        if (1 < tContext.switches.size() && !tContext.ports.contains(portNumber)) {
            tContext.switches.get(0).send(backflowRule(OFPort.of(portNumber)));
            tContext.ports.add(portNumber);
        }
    }

    public boolean decrementRefs(TVContext tContext, long xid) {
        if (tContext.refs.put(xid, tContext.refs.get(xid) - 1) == 1) {
            tContext.refs.remove(xid);
            return true;
        } else {
            return false;
        }

    }

    public void switch_connect(InterceptableMultiForwarder.Context context) {
        log.debug("switch_connect");
        OFMessage hello = factory.buildHello().setElements(Collections.<OFHelloElem>emptyList()).build();
        context.send(hello);
    }

    public void switch_hello(OFHello msg, InterceptableMultiForwarder.Context context) {
        log.debug("switch_hello");
        context.send(factory.buildFeaturesRequest().build());
    }

    public void switch_features_reply(OFFeaturesReply msg, InterceptableMultiForwarder.Context context) {
        log.debug("switch_features_reply");
        unassignedSwitches.put(msg.getDatapathId(), context);

        Set<DatapathId> unassignedDpids = unassignedSwitches.keySet();
        config.entrySet().stream()
            .filter(entry -> unassignedDpids.containsAll(entry.getValue()))
            .collect(singletonCollector())
            .ifPresent(entry -> {
                config.remove(entry.getKey());
                Pair<DatapathId, List<InterceptableMultiForwarder.Context>> ret = Pair.of(entry.getKey(), entry.getValue().stream()
                    .map(dpid -> unassignedSwitches.get(dpid))
                    .collect(Collectors.toList()));
                unassignedDpids.removeAll(entry.getValue());
                forwarder.establishControllerConnection(ret);
            });
    }

    public void switch_port_status(OFPortStatus msg, InterceptableMultiForwarder.Context context) {
        log.debug("switch_port_status");
        TVContext tContext = handlerContexts.get(context);
        if (tContext == null || context != tContext.switches.get(0)) {
            return;
        }

        OFPort port = msg.getDesc().getPortNo();
        if (port.equals(tablePortOut) || port.equals(tablePortIn)) {
            return;
        }

        updatePortBackflowRule(tContext, port);

        tContext.controller.send(msg);
    }

    public void switch_stats_reply(OFStatsReply msg_, InterceptableMultiForwarder.Context context) {
        log.debug("switch_stats_reply");
        TVContext tContext = handlerContexts.get(context);
        if (msg_ instanceof OFPortDescStatsReply) {
            if (context != tContext.switches.get(0)) {
                return;
            }

            OFPortDescStatsReply msg = (OFPortDescStatsReply) msg_;
            msg = msg.createBuilder()
                .setEntries(msg.getEntries().stream()
                    .filter(desc -> !desc.getPortNo().equals(tablePortOut))
                    .filter(desc -> !desc.getPortNo().equals(tablePortIn))
                    .collect(Collectors.toList())
                )
                .build();
    
            msg.getEntries().stream()
                .map(desc -> desc.getPortNo())
                .forEach(port -> updatePortBackflowRule(tContext, port));

            tContext.controller.send(msg);
        } else if (msg_ instanceof OFFlowStatsReply) {
            OFFlowStatsReply msg = (OFFlowStatsReply)msg_;
            
            tContext.entries.get(msg.getXid()).addAll(msg.getEntries());
            if (decrementRefs(tContext, msg.getXid())) {
                tContext.controller.send(msg.createBuilder()
                    .setEntries(tContext.entries.get(msg.getXid()))
                    .build());
                tContext.entries.remove(msg.getXid());
            }
        }
    }

    public void switch_packet_in(OFPacketIn msg, InterceptableMultiForwarder.Context context) {
        log.debug("switch_packet_in");
        TVContext tContext = handlerContexts.get(context);
        if (tContext == null) {
            return;
        }

        TableId tableId = TableId.of(tContext.switches.indexOf(context));
        
        // TODO inport is wrong for Stages except 0
        tContext.controller.send(msg.createBuilder()
            .setTableId(tableId)
            .build());
    }

    public void switch_barrier_reply(OFBarrierReply msg, InterceptableMultiForwarder.Context context) {
        log.debug("switch_barrier_reply");
        TVContext tContext = handlerContexts.get(context);
        if (decrementRefs(tContext, msg.getXid())) {
            tContext.controller.send(msg);
        }
    }

    public void controller_connect(InterceptableMultiForwarder.Context context, Object ret) {
        log.debug("controller_connect");
        OFMessage hello = factory.buildHello().setElements(Collections.<OFHelloElem>emptyList()).build();
        context.send(hello);

        @SuppressWarnings("unchecked")
        Pair<DatapathId, List<InterceptableMultiForwarder.Context>> entry = (Pair<DatapathId, List<InterceptableMultiForwarder.Context>>)ret;

        TVContext tContext = new TVContext(entry.first, context, entry.second);
        handlerContexts.put(context, tContext);
        for (InterceptableMultiForwarder.Context context_ : entry.second) {
            handlerContexts.put(context_, tContext);
        }
    }

    public void controller_hello(OFHello msg, InterceptableMultiForwarder.Context context) {
        log.debug("controller_hello");
        TVContext tContext = handlerContexts.get(context);
    }

    public void controller_features_request(OFFeaturesRequest msg, InterceptableMultiForwarder.Context context) {
        log.debug("controller_features_request");
        TVContext tContext = handlerContexts.get(context);
        context.send(factory.buildFeaturesReply()
            .setXid(msg.getXid())
            //TODO capabilities?
            .setDatapathId(tContext.dpid)
            .setNTables((short) tContext.switches.size())
            .build()
        );
    }

    public void controller_stats_request(OFStatsRequest msg_, InterceptableMultiForwarder.Context context) {
        log.debug("controller_stats_request");
        TVContext tContext = handlerContexts.get(context);
        if (msg_ instanceof OFPortDescStatsRequest) {
            OFPortDescStatsRequest msg = (OFPortDescStatsRequest)msg_;
            tContext.switches.get(0).send(msg);
        } else if (msg_ instanceof OFFlowStatsRequest) {
            OFFlowStatsRequest msg = (OFFlowStatsRequest)msg_;
        
            //TODO we assume ALL_TABLES and fully wildcarded matches (may need to rewrite messages)
            assert msg.getTableId().equals(TableId.ALL);
            tContext.entries.put(msg.getXid(), new ArrayList<OFFlowStatsEntry>());
            tContext.refs.put(msg.getXid(), tContext.switches.size());
            for (InterceptableMultiForwarder.Context sContext : tContext.switches) {
                sContext.send(msg);
            }
        }

    }

    public void controller_flow_mod(OFFlowMod msg, InterceptableMultiForwarder.Context context) {
        log.debug("controller_flow_mod");
        TVContext tContext = handlerContexts.get(context);
        int max_tableId = tContext.switches.size() - 1;
        int tableId = msg.getTableId().getValue();

        //TODO only works for wildcarded delete across all tables
        if (msg instanceof OFFlowDelete) {
            for (InterceptableMultiForwarder.Context sContext : tContext.switches) {
                sContext.send(msg);
            }
            return;
        }

        //Set TableId
        msg = msg.createBuilder()
            .setTableId(TableId.of(0))
            .build();

        //Replace Goto Table Instruction with Output Action        
        if (0 <= tableId && tableId < max_tableId) {
            assert !msg.getInstructions().stream()
            .filter(OFInstructionApplyActions.class::isInstance)
            .map(OFInstructionApplyActions.class::cast)
            .flatMap(instruction -> instruction.getActions().stream())
            .filter(OFActionOutput.class::isInstance)
            .map(OFActionOutput.class::cast)
            .filter(action ->
                Integer.toUnsignedLong(action.getPort().getPortNumber()) <
                Integer.toUnsignedLong(tablePortIn.getPortNumber()))
            .findAny()
            .isPresent();

            Optional<OFInstructionGotoTable> gotoInst = msg.getInstructions().stream()
                .filter(OFInstructionGotoTable.class::isInstance)
                .map(OFInstructionGotoTable.class::cast)
                .collect(singletonCollector());
            if (gotoInst.isPresent()) {
                assert gotoInst.get().getTableId().getValue() == tableId + 1;
                List<OFInstruction> instructions = Stream.concat(
                    msg.getInstructions().stream(),
                    msg.getInstructions().stream()
                        .filter(OFInstructionApplyActions.class::isInstance)
                        .findAny()
                        .isPresent() ? Stream.empty() : Stream.of(factory.instructions().buildApplyActions().build())
                ).collect(Collectors.toList());
                msg =  msg.createBuilder()
                    .setInstructions(instructions.stream()
                        .filter(inst -> !OFInstructionGotoTable.class.isInstance(inst))
                        .map(instruction -> {
                            if (instruction instanceof OFInstructionApplyActions) {
                                return ((OFInstructionApplyActions)instruction).createBuilder()
                                    .setActions(Stream.concat(
                                        ((OFInstructionApplyActions)instruction).getActions().stream(),
                                        Stream.of(factory.actions().buildOutput()
                                            .setPort(tablePortOut)
                                            .build())
                                    ).collect(Collectors.toList()))
                                    .build();
                            } else {
                                return instruction;
                            }
                        })
                        .collect(Collectors.toList()))
                    .build();
            }
        }

        //Add Match for tablePortIn in all Stages except 0
        if (0 < tableId && tableId <= max_tableId) {
            assert msg.getMatch().isFullyWildcarded(MatchField.IN_PORT);

            msg = msg.createBuilder()
                .setMatch(matchToBuilder((OFMatchV3)msg.getMatch())
                    .setExact(MatchField.IN_PORT, tablePortIn)
                    .build())
                .build();
        }

        //Output to tablePortOut and encode output
        if (max_tableId != 0 && tableId == max_tableId) {
            assert !msg.getInstructions().stream()
            .filter(OFInstructionGotoTable.class::isInstance)
            .findAny()
            .isPresent();

            msg = msg.createBuilder()
                .setInstructions(msg.getInstructions().stream()
                    .map(instruction -> Optional.of(instruction)
                        .filter(OFInstructionApplyActions.class::isInstance)
                        .map(OFInstructionApplyActions.class::cast)
                        .flatMap(applyActions -> applyActions.getActions().stream()
                            .filter(OFActionOutput.class::isInstance)
                            .map(OFActionOutput.class::cast)
                            .map(action -> action.getPort())
                            .filter(port ->
                                Integer.toUnsignedLong(port.getPortNumber()) <
                                Integer.toUnsignedLong(tablePortIn.getPortNumber()))
                            .collect(singletonCollector())
                            .map(port -> Pair.of(applyActions, port)))
                        .map(pair -> factory.instructions().buildApplyActions()
                            .setActions(Stream.concat(
                                Arrays.asList(
                                    factory.actions().buildPushVlan()
                                        .setEthertype(EthType.VLAN_FRAME)
                                        .build(),
                                    factory.actions().buildSetField()
                                        .setField(factory.oxms().vlanVid(OFVlanVidMatch.ofVlan(pair.second.getPortNumber())))
                                        .build()).stream(),
                                pair.first.getActions().stream()
                                    .map(action -> Optional.of(action)
                                        .filter(OFActionOutput.class::isInstance)
                                        .map(output -> factory.actions().buildOutput()
                                            .setPort(tablePortOut)
                                            .build())
                                        .map(OFAction.class::cast)
                                        .orElse(action))
                            ).collect(Collectors.toList()))
                            .build())
                        .map(OFInstruction.class::cast)
                        .orElse(instruction))
                    .collect(Collectors.toList()))
                .build();
        }

        tContext.switches.get(tableId).send(msg);
    }

    public void controller_packet_out(OFPacketOut msg, InterceptableMultiForwarder.Context context) {
        log.debug("controller_packet_out");
        TVContext tContext = handlerContexts.get(context);
        
        tContext.switches.get(0).send(msg);
    }


    public void controller_barrier_request(OFBarrierRequest msg, InterceptableMultiForwarder.Context context) {
        log.debug("controller_barrier_request");
        TVContext tContext = handlerContexts.get(context);
        tContext.refs.put(msg.getXid(), tContext.switches.size());
        for (InterceptableMultiForwarder.Context sContext : tContext.switches) {
            sContext.send(msg);
        }
    }

    public void echo_request(OFEchoRequest msg, InterceptableMultiForwarder.Context context) {
        context.send(factory.buildEchoReply().setXid(msg.getXid()).setData(msg.getData()).build());
    }

    public void error(OFErrorMsg msg, InterceptableMultiForwarder.Context context) {
        log.warn("received error message: {}", msg.getErrType());

        if (msg instanceof OFFlowModFailedErrorMsg) {
            log.warn("  {}", ((OFFlowModFailedErrorMsg)msg).getCode());
        }
    }

    @Override
    public synchronized void destroy() {
        reactorThread.interrupt();
        try {
            reactorThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        reactor.close();
        service.remove(this);
    }

    @Override
    public InetSocketAddress listenAddress() {
        return listenAddress;
    }

    @Override
    public void connectTo(List<InetSocketAddress> upstreamAddresses) {
        if (upstreamAddresses.size() > 1) {
            throw new RuntimeException("only supports one upstream controller");
        }
        if (upstreamAddresses.size() == 0) {
            proxy.connectTo(null);
        } else {
            proxy.connectTo(upstreamAddresses.get(0));
        }
    }

    @Override
    public String type() {
        return "tablevisor";
    }
}
