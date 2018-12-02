package com.github.sherter.jcon.examples.flowvisor;

import com.github.sherter.jcon.BufferingConsumer;
import com.github.sherter.jcon.InjectingConsumer;
import com.github.sherter.jcon.OFMessageChunker;
import com.github.sherter.jcon.OFMessageParsingConsumer;
import com.github.sherter.jcon.networking.Handler;
import com.github.sherter.jcon.networking.Reactor;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import net.floodlightcontroller.packet.Ethernet;
import org.projectfloodlight.openflow.protocol.*;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.EthType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * R. Sherwood, G. Gibb, K.-K. Yap, G. Appenzeller, M. Casado, N. McKeown und G. Parulkar.
 * "FlowVisor: A Network Virtualization Layer". Deutsche Telekom Inc. R&D Lab, Stanford University,
 * Nicira Networks, October 2009.
 *
 * <p>Limitations of this proof-of-concept implementation of FlowVisor:
 *
 * <ul>
 *   <li>No bandwith isolation (§4.1)
 *   <li>No topology isolation (§4.2)
 *   <li>No switch CPU isolation (§4.3)
 *   <li>No flow entries isolation (§4.5)
 * </ul>
 *
 * In essence, we only implement the bare minimum to get FlowSpace isolation (§4.4). Also, we assume
 * a specific topology and use a static flow space setup. The example setup looks like this:
 *
 * <pre>
 *     Topologie:
 *
 *       h1 (10.0.0.1/8) --------> SWITCH <------- h2 (10.0.0.2/8)
 *
 *     Slices:
 *       Guest c1: operates on ARP (or VLAN(ARP)) traffic
 *       Guest c2: operates on IPv4 (or VLAN(IPv4)) traffic
 *
 *     c1 runs a learning switch
 *     c2 runs an ipv4/port  router
 *  </pre>
 */
class FlowVisor {

  private static final Logger log = LoggerFactory.getLogger(FlowVisor.class);
  public static final OFFactory messageBuilder = OFFactories.getFactory(OFVersion.OF_10);

  private final Reactor reactor;

  private final XidManager xidManager = new XidManager();
  private final Map<Handler, SwitchConnectionContext> switchConnections = new HashMap<>();
  private final List<Slice> slices =
      ImmutableList.of(new Slice("127.0.0.1", 6634), new Slice("127.0.0.1", 6635));

  FlowVisor(Reactor reactor) {
    this.reactor = reactor;
  }

  Function<Handler, Handler.Callbacks> callbackFactoryForNewSwitchConnections() {
    return switchHandler -> {
      SwitchConnectionContext context = new SwitchConnectionContext(switchHandler);
      switchConnections.put(switchHandler, context);
      return new Handler.Callbacks(
          new BufferingConsumer(
              new OFMessageParsingConsumer(
                  new InjectingConsumer<>(this::receivedFromSwitch, context)),
              OFMessageChunker.INSTANCE),
          throwable -> this.switchDisconnected(throwable, context));
    };
  }

  void acceptConnectionFromSwitch(Handler switchHandler) {
    log.info("switch connected from {}", switchHandler.remoteAddress());
    OFHello hello = messageBuilder.buildHello().setXid(xidManager.create()).build();
    SwitchConnectionContext switchContext = switchConnections.get(switchHandler);
    switchContext.sender.accept(hello);

    log.info("establishing new connections with guest controllers");
    for (Slice s : slices) {
      try {
        reactor.establish(
            s.controllerAddress, callbackFactoryForNewControllerConnections(switchContext, s));
      } catch (IOException e) {
        log.error(
            "couldn't establish a connection with controller; start guest controllers first!");
        throw new RuntimeException(e);
      }
    }
  }

  private Function<Handler, Handler.Callbacks> callbackFactoryForNewControllerConnections(
      SwitchConnectionContext switchConnectionContext, Slice slice) {
    return controllerHandler -> {
      GuestConnectionContext guestConnectionContext =
          new GuestConnectionContext(controllerHandler, switchConnectionContext, slice);
      switchConnectionContext.addUpstream(guestConnectionContext);
      guestConnectionContext.sender.accept(messageBuilder.buildHello().build());
      return new Handler.Callbacks(
          new BufferingConsumer(
              new OFMessageParsingConsumer(
                  new InjectingConsumer<>(this::receivedFromController, guestConnectionContext)),
              OFMessageChunker.INSTANCE),
          throwable -> controllerDisconnected(throwable, guestConnectionContext));
    };
  }

  private void receivedFromSwitch(OFMessage message, SwitchConnectionContext context) {
    log.info("received from switch: {}", message);
    switch (message.getType()) {
      case HELLO:
        // we've already sent a HELLO message in acceptConnectionFromSwitch; ignore it
        break;
      case ERROR:
        break;
      case ECHO_REQUEST:
        sendEchoReply((OFEchoRequest) message, context.sender);
        break;
      case ECHO_REPLY:
      case FEATURES_REPLY:
      case GET_CONFIG_REPLY:
      case STATS_REPLY:
      case BARRIER_REPLY:
      case QUEUE_GET_CONFIG_REPLY:
        // for replies, the xid unambiguously identifies the correct guest controller
        replaceXidAndForwardToGuest(message);
        break;
      default:
        // determine which guest controller should receive the message
        belongingSlices(message)
            .forEach(
                s -> {
                  context.upstreamContextOf(s).sender.accept(message);
                });
    }
  }

  private void sendEchoReply(OFEchoRequest echoRequest, Consumer<OFMessage> sender) {
    sender.accept(
        messageBuilder
            .buildEchoReply()
            .setXid(echoRequest.getXid())
            .setData(echoRequest.getData())
            .build());
  }

  private void replaceXidAndForwardToGuest(OFMessage message) {
    XidContext xidContext = xidManager.remove(message.getXid());
    xidContext.guestConnectionContext.sender.accept(
        message.createBuilder().setXid(xidContext.guestXid).build());
  }

  private Collection<Slice> belongingSlices(OFMessage message) {
    if (message.getType() == OFType.PACKET_IN) {
      OFPacketIn packetIn = (OFPacketIn) message;
      byte[] data = packetIn.getData();
      Ethernet ethernet = new Ethernet();
      ethernet.deserialize(data, 0, data.length);
      EthType ethType = ethernet.getEtherType();
      if (ethType == EthType.ARP) {
        return ImmutableList.of(slices.get(0));
      } else if (ethType == EthType.IPv4) {
        return ImmutableList.of(slices.get(1));
      } else {
        return ImmutableList.of();
      }
    }
    return slices;
  }

  private void receivedFromController(OFMessage message, GuestConnectionContext context) {
    log.info("received from controller ({}): {}", context.handler.remoteAddress(), message);
    switch (message.getType()) {
      case HELLO:
        // ignore, we've already send HELLO
        break;
      case FLOW_MOD:
        OFFlowMod flowMod = addSliceSpecificMatch((OFFlowMod) message, context.slice);
        replaceXidAndForward(flowMod, context);
        break;
      default:
        replaceXidAndForward(message, context);
    }
  }

  private OFFlowMod addSliceSpecificMatch(OFFlowMod flowMod, Slice slice) {
    if (slice == slices.get(0)) {
      Match newMatch =
          flowMod.getMatch().createBuilder().setExact(MatchField.ETH_TYPE, EthType.ARP).build();
      return flowMod.createBuilder().setMatch(newMatch).build();
    } else {
      Match newMatch =
          flowMod.getMatch().createBuilder().setExact(MatchField.ETH_TYPE, EthType.IPv4).build();
      return flowMod.createBuilder().setMatch(newMatch).build();
    }
  }

  private void replaceXidAndForward(OFMessage message, GuestConnectionContext guestContext) {
    XidContext xidContext = xidManager.create(message.getXid(), guestContext);
    guestContext.switchConnectionContext.sender.accept(
        message.createBuilder().setXid(xidContext.flowVisorXid).build());
  }

  private void switchDisconnected(Throwable cause, SwitchConnectionContext context) {
    log.info(
        "switch connection broke (remote address: {}); cause: {}",
        context.handler.remoteAddress(),
        cause == null ? "disconnected" : cause.getMessage());
    switchConnections.remove(context.handler);
    context.upstreamContexts().forEach(c -> c.handler.close());
  }

  private void controllerDisconnected(Throwable cause, GuestConnectionContext context) {
    SocketAddress localAddress = context.handler.localAddress();
    log.info(
        "controller connection broke (local address: {}); cause: {}",
        localAddress == null ? "not connected yet" : localAddress,
        cause == null ? "disconnected" : cause.getMessage());
    context.switchConnectionContext.handler.close();
  }
}
