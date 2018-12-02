package com.github.sherter.jcon.composer;

import com.github.sherter.jcon.InterceptableForwarder;
import com.github.sherter.jcon.InterceptableForwarder.Context;
import com.github.sherter.jcon.networking.Reactor;
import com.github.sherter.jcon.networking.Handler;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.Random;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.Optional;
import java.util.stream.Collectors;
import org.projectfloodlight.openflow.protocol.*;
import org.projectfloodlight.openflow.protocol.instruction.*;
import org.projectfloodlight.openflow.protocol.action.*;
import org.projectfloodlight.openflow.protocol.match.*;
import org.projectfloodlight.openflow.types.*;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.LLDP;
import net.floodlightcontroller.packet.LLDPTLV;
import lombok.Data;
import lombok.AllArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.JsonNode;

public class Pbce2Layer implements Layer {

    private static final Logger log = LoggerFactory.getLogger(Pbce2Layer.class);

    private final LayerService service;
    private final Reactor reactor;
    private final RoutingProxy proxy;
    private final InterceptableForwarder forwarder;
    private final InetSocketAddress listenAddress;

    private final BiMap<DatapathId, Context> dpidToContext;

    private final Map<Pair<Context, Integer>, Integer> evToDl;

    // TODO track time & clean up sometimes
    private final Map<Pair<LLDPTLV, LLDPTLV>, Pair<Context, Integer>> lldpCache;

    private final Map<Context, Set<Integer>> ports;
    // TODO remove if no recent lldp packets
    private final Map<Pair<Context, Integer>, Pair<Context, Integer>> neigh;

    private final Map<Pair<Context, Integer>, Pair<U64, U64>> utilPrev;
    private final Map<Pair<Context, Integer>, Double> util;
    private final Map<Pair<Context, Integer>, Integer> arrvCount;
    private final Map<Pair<Context, Integer>, Double> arrv;

    private final Map<Pair<DatapathId, Integer>, Integer> staticEvictions;

    // TODO remove entries after timeout
    private final Random random;
    private final Set<Long> xids;

    private final Thread reactorThread;
    private final Thread decisionThread;

    private final int lower;
    private final int upper;
    private final int upper2;
    private final long gracePeriod;
    private final long dc;
    private final Double weight;

    private long graceTime;

    @Data
    @AllArgsConstructor(staticName = "of")
    private static class Pair<A, B> {
        private A first;
        private B second;
    }

    public Pbce2Layer(LayerService service, JsonNode config) throws IOException {
        this.service = service;
        reactor = new Reactor(Selector.open());
        proxy = RoutingProxy.listenOn(reactor, null);

        forwarder = new InterceptableForwarder.Builder(reactor, proxy.listenAddress())
                .interceptUpstream(OFPacketIn.class, this::packetIn)
                .interceptUpstream(OFFeaturesReply.class, this::featuresReply)
                .interceptUpstream(OFPortStatus.class, this::portStatus)
                .interceptUpstream(OFStatsReply.class, this::statsReply)
                .interceptDownstream(OFPacketOut.class, this::packetOut)
                .interceptDownstream(OFFlowMod.class, this::flowMod)
                .build();
        listenAddress = forwarder.listenOn(new InetSocketAddress(6654));

        dpidToContext = HashBiMap.create();

        evToDl = new HashMap<Pair<Context, Integer>, Integer>();

        lldpCache = new HashMap<Pair<LLDPTLV, LLDPTLV>, Pair<Context, Integer>>();

        ports = new HashMap<Context, Set<Integer>>();
        neigh = new HashMap<Pair<Context, Integer>, Pair<Context, Integer>>();

        utilPrev = new HashMap<Pair<Context, Integer>, Pair<U64, U64>>();
        util = new HashMap<Pair<Context, Integer>, Double>();
        arrvCount = new HashMap<Pair<Context, Integer>, Integer>();
        arrv = new HashMap<Pair<Context, Integer>, Double>();

        random = new Random();
        xids = new HashSet<Long>();

        if (config.has("lower") && config.get("lower").isInt()) {
            lower = config.get("lower").asInt();
        } else {
            lower = 100;
        }

        if (config.has("upper") && config.get("upper").isInt()) {
            upper = config.get("upper").asInt();
        } else {
            upper = 200;
        }

        if (config.has("upper2") && config.get("upper2").isInt()) {
            upper2 = config.get("upper2").asInt();
        } else {
            upper2 = 200;
        }

        if (config.has("gracePeriod") && config.get("gracePeriod").isDouble()) {
            gracePeriod = (long) config.get("gracePeriod").asDouble() * 1000000000;
        } else {
            gracePeriod = 1000000000;
        }

        if (config.has("dc") && config.get("dc").isDouble()) {
            dc = (long) config.get("dc").asDouble() * 1000;
        } else {
            dc = 1000;
        }

        if (config.has("weight") && config.get("weight").isDouble()) {
            weight = config.get("weight").asDouble();
        } else {
            weight = 0.5;
        }

        graceTime = 0;

        staticEvictions = new HashMap<Pair<DatapathId, Integer>, Integer>();
        if (config.has("static_evictions") && config.get("static_evictions").isObject()) {
            Iterator<Map.Entry<String, JsonNode>> sIt = config.get("static_evictions").fields();
            while (sIt.hasNext()) {
                Map.Entry<String, JsonNode> sField = sIt.next();
                DatapathId dpid = DatapathId.of(sField.getKey());
                Iterator<Map.Entry<String, JsonNode>> pIt = sField.getValue().fields();
                while (pIt.hasNext()) {
                    Map.Entry<String, JsonNode> pField = pIt.next();
                    int evport = Integer.parseInt(pField.getKey());
                    int dlport = pField.getValue().asInt();
                    staticEvictions.put(Pair.of(dpid, evport), dlport);
                    log.info("register static eviction {}-{}: {}", dpid, evport, dlport);
                }
            }
        }

        reactorThread = new Thread(() -> reactor.loop());
        reactorThread.start();

        decisionThread = new Thread(() -> decisionThread());
        decisionThread.start();
    }

    private static boolean is_dscp_in(IpDscp dscp) {
        int port = dscp.getDscpValue();
        return 0 < port && port < 32;
    }

    private static int dscp_in(IpDscp dscp) {
        int port = dscp.getDscpValue();
        assert(0 < port && port < 32);
        return port;
    }

    private static IpDscp in_dscp(int port) {
        assert(0 < port && port < 32);
        return IpDscp.of((byte) port);
    }

    private static int dscp_out(IpDscp dscp) {
        int port = dscp.getDscpValue() - 32;
        assert(0 < port && port < 32);
        return port;
    }

    private static IpDscp out_dscp(int port) {
        assert(0 < port && port < 32);
        return IpDscp.of((byte) (port + 32));
    }

    private static Optional<Integer> getOutputPort(List<OFAction> actions) {
        return actions.stream()
            .filter(OFActionOutput.class::isInstance)
            .map(OFActionOutput.class::cast)
            .findAny()
            .map(action -> action.getPort().getPortNumber());
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

    private static OFMatchV3.Builder matchToBuilder(OFFactory factory, OFMatchV3 match) {
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

    private static OFMessage evictionRule(OFFactory factory, boolean add, int evport, int dlport) {
        return ((add) ? factory.buildFlowAdd() : factory.buildFlowDelete())
            .setOutPort(OFPort.ANY)
            .setOutGroup(OFGroup.ANY)
            .setPriority(2)
            .setMatch(factory.buildMatch()
                .setExact(MatchField.IN_PORT, OFPort.of(evport))
                .setExact(MatchField.IP_DSCP, IpDscp.of((byte) 0))
                .setExact(MatchField.ETH_TYPE, EthType.IPv4)
                .build()
            )
            .setInstructions(Arrays.asList(
                factory.instructions().buildApplyActions()
                    .setActions(Arrays.asList(
                        factory.actions().buildSetField()
                            .setField(factory.oxms().buildIpDscp()
                                .setValue(in_dscp(evport))
                                .build()
                            )
                            .build(),
                        factory.actions().buildOutput()
                            .setPort((evport == dlport) ? OFPort.IN_PORT : OFPort.of(dlport))
                            .build()
                    ))
                    .build()
            ))
            .build();
    }

    private static List<OFMessage> backflowRule(OFFactory factory, boolean add, int outport) {
        return Arrays.asList(
            ((add) ? factory.buildFlowAdd() : factory.buildFlowDelete())
                .setOutPort(OFPort.ANY)
                .setOutGroup(OFGroup.ANY)
                .setPriority(3)
                .setMatch(factory.buildMatch()
                    .setExact(MatchField.IN_PORT, OFPort.of(outport))
                    .setExact(MatchField.ETH_TYPE, EthType.IPv4)
                    .setExact(MatchField.IP_DSCP, out_dscp(outport))
                    .build()
                )
                .setInstructions(Arrays.asList(
                    factory.instructions().buildApplyActions()
                        .setActions(Arrays.asList(
                            factory.actions().buildSetField()
                                .setField(factory.oxms().buildIpDscp()
                                    .setValue(IpDscp.of((byte) 0))
                                    .build()
                                )
                                .build(),
                            factory.actions().buildOutput()
                                .setPort(OFPort.IN_PORT)
                                .build()
                        ))
                        .build()
                ))
                .build(),
            ((add) ? factory.buildFlowAdd() : factory.buildFlowDelete())
                .setOutPort(OFPort.ANY)
                .setOutGroup(OFGroup.ANY)
                .setPriority(2)
                .setMatch(factory.buildMatch()
                    .setExact(MatchField.ETH_TYPE, EthType.IPv4)
                    .setExact(MatchField.IP_DSCP, out_dscp(outport))
                    .build()
                )
                .setInstructions(Arrays.asList(
                    factory.instructions().buildApplyActions()
                        .setActions(Arrays.asList(
                            factory.actions().buildSetField()
                                .setField(factory.oxms().buildIpDscp()
                                    .setValue(IpDscp.of((byte) 0))
                                    .build()
                                )
                                .build(),
                            factory.actions().buildOutput()
                                .setPort(OFPort.of(outport))
                                .build()
                        ))
                        .build()
                ))
                .build()
        );
    }

    private static OFPacketOut rewritePacketOut(OFFactory factory, OFPacketOut msg, int export) {
        // TODO: If there exist multiple output actions, this might not reliably work
        int outport = getOutputPort(msg.getActions()).get();
        if (OFPort.of(outport).equals(OFPort.FLOOD))
            return msg;
        Stream<OFAction> actions = msg.getActions().stream()
            .map(action -> (action instanceof OFActionOutput) ?
                factory.actions().buildOutput()
                    .setPort(OFPort.IN_PORT)
                    .build() : action
            );
        return msg.createBuilder()
            .setInPort(OFPort.of(export))
            .setActions(Stream.concat(
                Stream.of(factory.actions().buildSetField()
                    .setField(factory.oxms().buildIpDscp()
                        .setValue(out_dscp(outport))
                        .build()
                    )
                    .build()
                ),
                actions
            ).collect(Collectors.toList()))
            .build();
    }

    private static OFFlowMod rewriteFlowMod(OFFactory factory, OFFlowMod msg, int export) {
        // TODO: If there exist multiple output actions, this might not reliably work
        int outport = msg.getInstructions().stream()
            .filter(OFInstructionApplyActions.class::isInstance)
            .map(OFInstructionApplyActions.class::cast)
            .flatMap(instruction -> instruction.getActions().stream())
            .filter(OFActionOutput.class::isInstance)
            .map(OFActionOutput.class::cast)
            .findAny()
            .map(action -> action.getPort().getPortNumber())
            .get();
        List<OFInstruction> instructions = msg.getInstructions().stream()
            .map(instruction -> {
                if (instruction instanceof OFInstructionApplyActions) {
                    return factory.instructions().buildApplyActions()
                        .setActions(Stream.concat(
                            Stream.of(factory.actions().buildSetField()
                                .setField(factory.oxms().buildIpDscp()
                                    .setValue(out_dscp(outport))
                                    .build()
                                )
                                .build()
                            ),
                            ((OFInstructionApplyActions) instruction).getActions().stream()
                                .map(action -> (action instanceof OFActionOutput) ?
                                    factory.actions().buildOutput()
                                        .setPort(OFPort.IN_PORT)
                                        .build() : action
                                )
                        ).collect(Collectors.toList()))
                        .build();
                } else {
                    return instruction;
                }
            }).collect(Collectors.toList());
        return msg.createBuilder()
            .setPriority(3)
            .setMatch(matchToBuilder(OFFactories.getFactory(msg.getVersion()), (OFMatchV3)msg.getMatch())
                .setExact(MatchField.IN_PORT, OFPort.of(export))
                .setExact(MatchField.IP_DSCP, in_dscp(
                    msg.getMatch().get(MatchField.IN_PORT).getPortNumber())
                )
                .build()
            )
            .setInstructions(instructions)
            .build();
    }

    private static OFFlowMod rewriteFlowModUnchanged(OFFactory factory, OFFlowMod msg) {
        return msg.createBuilder()
            .setPriority(3)
            .setMatch(matchToBuilder(OFFactories.getFactory(msg.getVersion()), (OFMatchV3)msg.getMatch())
                .setExact(MatchField.IP_DSCP, IpDscp.DSCP_0)
                .build())
            .build();
    }

    private void decisionThread() {
        try {
            while (true) {
                Thread.sleep(dc);
                decisionCycle();
            }
        } catch (InterruptedException e)  {
            // Do nothing
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    private void evictPort(OFFactory factory, Context context, int evport, int dlport, boolean firstEviction) {
        evToDl.put(Pair.of(context, evport), dlport);

        if (firstEviction) {
            for (int port : ports.get(context)) {
                for (OFMessage backflow : backflowRule(factory, true, port)) {
                    context.sendDownstream(backflow);
                }
            }
        }

        context.sendDownstream(evictionRule(factory, true, evport, dlport));
    }

    private void unevictPort(OFFactory factory, Context context, int evport, boolean lastEviction) {
        Pair<Context, Integer> evKey = Pair.of(context, evport);

        context.sendDownstream(evictionRule(
            factory, false, evport, evToDl.get(evKey)
        ));

        evToDl.remove(evKey);

        //TODO remove after half decision cycle
        if (lastEviction) {
            for (int port : ports.get(context)) {
                for (OFMessage backflow : backflowRule(factory, false, port)) {
                    context.sendDownstream(backflow);
                }
            }
        }
    }

    private void updateStaticEvictions() {
        for (Map.Entry<Pair<DatapathId, Integer>, Integer> entry : staticEvictions.entrySet()) {
            if (!dpidToContext.containsKey(entry.getKey().first)) {
                continue;
            }
            Context context = dpidToContext.get(entry.getKey().first);
            Integer evport = entry.getKey().second;
            Integer dlport = entry.getValue();

            if (evToDl.containsKey(Pair.of(context, evport)) ||
                !ports.get(context).contains(evport) ||
                !ports.get(context).contains(dlport)) {
                continue;
            }

            boolean firstEviction = !ports.get(context).stream()
                .filter(port -> evToDl.containsKey(Pair.of(context, port)))
                .findAny()
                .isPresent();
            log.info("install static eviction");
            evictPort(OFFactories.getFactory(OFVersion.OF_13), context, evport, dlport, firstEviction);
        }
    }

    private synchronized void decisionCycle() {
        OFFactory factory = OFFactories.getFactory(OFVersion.OF_13);
        for (Context context : ports.keySet()) {
            if (!dpidToContext.inverse().get(context).equals(DatapathId.of("1"))) {
                continue;
            }

            long xidP = random.nextLong();
            context.sendDownstream(factory.buildPortStatsRequest()
                .setXid(xidP)
                .build());
            xids.add(xidP);
            long xidT = random.nextLong();
            context.sendDownstream(factory.buildTableStatsRequest()
                .setXid(xidT)
                .build());
            xids.add(xidT);
        }
    }

    private synchronized void packetIn(OFPacketIn msg, InterceptableForwarder.Context context) {
        //log.info("received PacketIn");
        byte[] ethernetData = msg.getData();
        Ethernet eth = new Ethernet();

        if (eth.deserialize(ethernetData, 0, ethernetData.length) == null) {
            log.info("received empty PacketIn");
            context.sendDownstream(msg);
            return;
        }

        if (eth.getEtherType().equals(EthType.IPv4)) {
            IPv4 ipv4 = (IPv4) eth.getPayload();
            IpDscp dscp = IpDscp.of((byte) ((ipv4.getDiffServ() >> 2) & 0x3F));
            int export = msg.getMatch().get(MatchField.IN_PORT).getPortNumber();

            if (is_dscp_in(dscp) && neigh.containsKey(Pair.of(context, export))) {
                //log.info("rewrite evicted PacketIn");
                int inport = dscp_in(dscp);     
                //ipv4.setDiffServ((byte) 0);
                context = neigh.get(Pair.of(context, export)).first;
                msg = msg.createBuilder()
                    .setMatch(matchToBuilder(OFFactories.getFactory(msg.getVersion()), (OFMatchV3)msg.getMatch())
                        .setExact(MatchField.IN_PORT, OFPort.of(inport))
                        .build())
                    .setData(eth.serialize())
                    .build();
            } else if (dscp.getDscpValue() != 0) {
                log.info("received backflow/invalid evicted packetIn");
                return;
            }
        } else if (eth.getEtherType().equals(EthType.LLDP)) {
            int port = msg.getMatch().get(MatchField.IN_PORT).getPortNumber();
            LLDP lldp = (LLDP) eth.getPayload();
            neigh.put(
                lldpCache.get(Pair.of(lldp.getChassisId(), lldp.getPortId())),
                Pair.of(context, port)
            );            
        }

        context.sendUpstream(msg);
    }

    private synchronized void packetOut(OFPacketOut msg, InterceptableForwarder.Context context) {
        // TODO: Stabilize by tracking packets
        //log.info("received PacketOut");

        byte[] ethernetData = msg.getData();
        Ethernet eth = new Ethernet();
        if (eth.deserialize(ethernetData, 0, ethernetData.length) == null) {
            log.info("received empty PacketOut");
            context.sendDownstream(msg);
            return;
        }

        if (eth.getEtherType().equals(EthType.IPv4)) {
            int inport = msg.getInPort().getPortNumber();

            if (evToDl.containsKey(Pair.of(context, inport))) {
                int dlport = evToDl.get(Pair.of(context, inport));
                Pair<Context, Integer> ex = neigh.get(Pair.of(context, dlport));
                context = ex.first;
                msg = rewritePacketOut(OFFactories.getFactory(msg.getVersion()), msg, ex.second);
            }
        } else if (eth.getEtherType().equals(EthType.LLDP)) {
            LLDP lldp = (LLDP) eth.getPayload();
            lldpCache.put(
                Pair.of(lldp.getChassisId(), lldp.getPortId()),
                Pair.of(context, getOutputPort(msg.getActions()).get())
            );
        }
        context.sendDownstream(msg);
    }

    private synchronized void flowMod(OFFlowMod msg, InterceptableForwarder.Context context) {
        //log.info("received FlowMod");
        if (!msg.getMatch().isFullyWildcarded(MatchField.IN_PORT) && 
            msg.getMatch().isExact(MatchField.ETH_TYPE) && 
            msg.getMatch().get(MatchField.ETH_TYPE).equals(EthType.IPv4)) {
            int inport = msg.getMatch().get(MatchField.IN_PORT).getPortNumber();
            arrvCount.put(
                Pair.of(context, inport),
                arrvCount.getOrDefault(Pair.of(context, inport), 0) + 1
            );
            if (evToDl.containsKey(Pair.of(context, inport))) {
                int dlport = evToDl.get(Pair.of(context, inport));
                Pair<Context, Integer> ex = neigh.get(Pair.of(context, dlport));
                context = ex.first;
                msg = rewriteFlowMod(OFFactories.getFactory(msg.getVersion()), msg, ex.second);
            } else {
                msg = rewriteFlowModUnchanged(OFFactories.getFactory(msg.getVersion()), msg);
            }
        }
        context.sendDownstream(msg);
    }

    // TODO Somehow handle switch disconnect

    private synchronized void featuresReply(OFFeaturesReply msg, InterceptableForwarder.Context context) {
        log.info("received FeaturesReply");
        long xidF = random.nextLong();
        context.sendDownstream(OFFactories.getFactory(msg.getVersion())
            .buildPortDescStatsRequest()
                .setXid(xidF)
                .build());
        xids.add(xidF);
        dpidToContext.put(msg.getDatapathId(), context);
        ports.putIfAbsent(context, new HashSet<Integer>());
        context.sendUpstream(msg);
    }

    private synchronized void portStatus(OFPortStatus msg, InterceptableForwarder.Context context) {
        log.info("received PortStatus");
        // TODO add/delete backflow rules for switches that have evicted ports
        int port = msg.getDesc().getPortNo().getPortNumber();
        ports.putIfAbsent(context, new HashSet<Integer>());
        if (msg.getReason().equals(OFPortReason.ADD)) {
            ports.get(context).add(port);
        } else if (msg.getReason().equals(OFPortReason.DELETE)) {
            ports.get(context).remove(port);
        }
        updateStaticEvictions();
        context.sendUpstream(msg);
    }

    private synchronized void statsReply(OFStatsReply _msg, InterceptableForwarder.Context context) {
        log.info("received StatsReply");
        if (xids.contains(_msg.getXid())) {
            xids.remove(_msg.getXid());
            if (_msg instanceof OFPortDescStatsReply) {
                OFPortDescStatsReply msg = (OFPortDescStatsReply) _msg;
                ports.get(context).addAll(msg.getEntries()
                    .stream()
                    .map(portDesc -> portDesc.getPortNo())
                    .filter(port -> !port.equals(OFPort.LOCAL))
                    .map(port -> port.getPortNumber())
                    .collect(Collectors.toCollection(HashSet::new)));
                updateStaticEvictions();
            } else if (_msg instanceof OFPortStatsReply) {
                OFPortStatsReply msg = (OFPortStatsReply) _msg;
                for (OFPortStatsEntry entry : msg.getEntries()) {
                    Pair<Context, Integer> key = Pair.of(context, entry.getPortNo().getPortNumber());
                    Pair<U64, U64> utilPrevV = utilPrev.getOrDefault(key, Pair.of(U64.of(0), U64.of(0)));
                    U64 drx_bytes = entry.getRxBytes().subtract(utilPrevV.first);
                    U64 dtx_bytes = entry.getTxBytes().subtract(utilPrevV.second);
                    U64 dx_bytes = drx_bytes.add(dtx_bytes);
                    utilPrev.put(key, Pair.of(entry.getRxBytes(), entry.getTxBytes()));
                    util.put(key, weight * ((double) dx_bytes.getValue()) + (1.0 - weight) * util.getOrDefault(key, 0.0));
                }
            } else if (_msg instanceof OFTableStatsReply) {
                if (System.nanoTime() < graceTime) {
                    return;
                }

                OFTableStatsReply msg = (OFTableStatsReply) _msg;
                long active = msg.getEntries().stream()
                    .filter(entry -> entry.getTableId().getValue() == 0)
                    .findAny()
                    .map(entry -> entry.getActiveCount())
                    .get();

                for (int port : ports.get(context)) {
                    Pair<Context, Integer> key = Pair.of(context, port);
                    arrv.put(key, weight * arrv.getOrDefault(key, 0.0) + (1.0 - weight) * arrvCount.getOrDefault(key, 0));
                    arrvCount.put(key, 0);
                }

                if (active < lower) {
                    int evictions = 0;
                    int evport = 0;
                    double evarrv = Double.POSITIVE_INFINITY;
                    for (int port : ports.get(context)) {
                        Pair<Context, Integer> key = Pair.of(context, port);
                        if (!evToDl.containsKey(key) ||
                            staticEvictions.containsKey(Pair.of(
                                dpidToContext.inverse().get(context), port))) {
                            continue;
                        }
                        evictions += 1;
                        if (arrv.getOrDefault(key, 0.0) < evarrv) {
                            evport = port;
                            evarrv = arrv.getOrDefault(key, 0.0);
                        }
                    }

                    if (evarrv == Double.POSITIVE_INFINITY) {
                        return;
                    }

                    log.info("unevicting port {}", evport);

                    graceTime = System.nanoTime() + gracePeriod;

                    unevictPort(
                        OFFactories.getFactory(msg.getVersion()),
                        context, evport, evictions == 1
                    );
                } else if (upper < active) {
                    boolean anyEvictions = false;
                    int evport = 0;
                    double evarrv = Float.NEGATIVE_INFINITY;
                    for (int port : ports.get(context)) {
                        Pair<Context, Integer> key = Pair.of(context, port);
                        if (evToDl.containsKey(key)) {
                            anyEvictions = true;
                            continue;
                        }
                        if (evarrv < arrv.getOrDefault(key, 0.0)) {
                            evport = port;
                            evarrv = arrv.getOrDefault(key, 0.0);
                        }
                    }

                    /*
                    if (anyEvictions && active < upper2) {
                        graceTime = System.nanoTime() + gracePeriod;
                        return;
                    }
                    */

                    if (evarrv == Float.NEGATIVE_INFINITY) {
                        return;
                    }

                    int dlport = 0;
                    double dlutil = Float.POSITIVE_INFINITY;
                    for (int port : ports.get(context)) {
                        if (neigh.containsKey(Pair.of(context, port)) &&
                            util.getOrDefault(Pair.of(context, port), 0.0) < dlutil &&
                            //dpidToContext.inverse().containsKey(neigh.get(Pair.of(context, port)).first) &&
                            dpidToContext.inverse().get(neigh.get(Pair.of(context, port)).first).equals(DatapathId.of("3"))) {
                            dlport = port;
                            dlutil = util.getOrDefault(Pair.of(context, port), 0.0);
                        }
                    }

                    if (dlutil == Float.POSITIVE_INFINITY) {
                        return;
                    }

                    log.info("evicting port {} over {}", evport, dlport);

                    graceTime = System.nanoTime() + gracePeriod;

                    evictPort(
                        OFFactories.getFactory(msg.getVersion()),
                        context, evport, dlport, !anyEvictions
                    );
                }
            }
        } else {
            context.sendUpstream(_msg);
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
        decisionThread.interrupt();
        try {
            decisionThread.join();
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
        return "pbce2";
    }
}
