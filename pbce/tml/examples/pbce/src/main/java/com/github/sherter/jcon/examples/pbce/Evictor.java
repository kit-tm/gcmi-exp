package com.github.sherter.jcon.examples.pbce;

import static com.google.common.base.Predicates.not;
import static org.projectfloodlight.openflow.protocol.match.MatchField.IN_PORT;
import static org.projectfloodlight.openflow.protocol.match.MatchField.VLAN_VID;

import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.stream.Collectors;
import org.projectfloodlight.openflow.protocol.OFFlowAdd;
import org.projectfloodlight.openflow.protocol.OFFlowDelete;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.action.OFActionOutput;
import org.projectfloodlight.openflow.protocol.action.OFActionSetVlanVid;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.OFVlanVidMatch;
import org.projectfloodlight.openflow.types.VlanVid;

class Evictor {

  private final Pbce.Context context;

  Evictor(Pbce.Context context) {
    this.context = context;
  }

  private OFFlowAdd createEvictionRule(OFPort evictionPort, OFPort delegationPort) {
    Match match =
        context
            .factory
            .buildMatch()
            .setExact(IN_PORT, evictionPort)
            // we can only delegate non-VLAN traffic, as we use this field internally
            .setExact(VLAN_VID, OFVlanVidMatch.UNTAGGED)
            .build();
    OFActionSetVlanVid writeMetadata =
        context.factory.actions().setVlanVid(VlanVid.ofVlan(evictionPort.getPortNumber()));

    OFActionOutput output =
        context.factory.actions().buildOutput().setPort(delegationPort).setMaxLen(0xFFff).build();
    return context
        .factory
        .buildFlowAdd()
        .setMatch(match)
        .setActions(ImmutableList.of(writeMetadata, output))
        .setPriority(10)
        .build();
  }

  private OFFlowAdd createBackflowRule(OFPort delegationPort, OFPort outputPort) {
    Match match =
        context
            .factory
            .buildMatch()
            .setExact(IN_PORT, delegationPort)
            .setExact(VLAN_VID, OFVlanVidMatch.ofVlanOF10(Pbce.mapToVlanId(outputPort)))
            .build();
    OFAction clearVlan = context.factory.actions().stripVlan();
    OFAction output = context.factory.actions().buildOutput().setPort(outputPort).build();
    return context
        .factory
        .buildFlowAdd()
        .setMatch(match)
        .setActions(ImmutableList.of(clearVlan, output))
        .setPriority(20)
        .setCookie(Pbce.COOKIE)
        .build();
  }

  void revert(Pbce.Context extensionSwitch) {
    context.isDelegationSwitch = false;
    context.isExtensionSwitch = false;
    extensionSwitch.isExtensionSwitch = false;
    extensionSwitch.isDelegationSwitch = false;

    OFFlowDelete delete = context.factory.buildFlowDelete().setCookie(Pbce.COOKIE).build();
    context.switchConnection.accept(delete);
  }

  void evict(OFPort delegationPort, Pbce.Context extensionSwitch, OFPort extensionPort) {

    // as we can only redirect all or non of the FLOW_MOD messages send to the delegation switch
    // (could we use the buffer_id to categorize them?), all ports (except for the delegation port) must become eviction ports.
    // Otherwise a packet received on a non-eviction port might trigger a packet_in, but the controller's FLOW_MOD message
    // never reaches the delegation switch, because it was redirected to the extension switch

    context.isDelegationSwitch = true;
    extensionSwitch.isExtensionSwitch = true;
    extensionSwitch.extensionPorts.add(extensionPort);
    extensionSwitch.delegationSwitchContext = context;
    context.extensionSwitchContext = extensionSwitch;
    context.extensionPort = extensionPort;

    context
        .switchPorts
        .stream()
        .filter(not(delegationPort::equals))
        .map(p -> createEvictionRule(p, delegationPort))
        .forEach(context.switchConnection);

    List<OFFlowAdd> backflowRules =
        context
            .switchPorts
            .stream()
            .filter(not(delegationPort::equals))
            .map(p -> createBackflowRule(delegationPort, p))
            .collect(Collectors.toList());

    backflowRules.add(createBackflowRule(delegationPort, OFPort.FLOOD));

    backflowRules.forEach(flow -> context.switchConnection.accept(flow));
  }
}
