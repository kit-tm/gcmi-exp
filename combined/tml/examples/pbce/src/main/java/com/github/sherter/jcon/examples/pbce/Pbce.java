package com.github.sherter.jcon.examples.pbce;

import com.github.sherter.jcon.*;
import com.github.sherter.jcon.networking.Handler;
import com.github.sherter.jcon.networking.Reactor;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.MoreCollectors;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.floodlightcontroller.packet.Ethernet;
import org.projectfloodlight.openflow.protocol.*;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.action.OFActionOutput;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Pbce {

  private static final Logger log = LoggerFactory.getLogger(Pbce.class);

  static final U64 COOKIE = U64.of(98127398123876l);

  private final SocketAddress upstreamAddress;
  private final Reactor reactor;
  private final Map<Handler, Context> contexts = new HashMap<>();

  static final short FLOOD_VLAN_ID = 16;

  public Pbce(SocketAddress upstreamAddress, Reactor reactor) {
    this.upstreamAddress = upstreamAddress;
    this.reactor = reactor;
  }

  public SocketAddress listenOn(@Nullable InetSocketAddress listenAddress) throws IOException {
    ServerSocketChannel channel =
        reactor.listen(
            listenAddress,
            callbackFactoryForNewSwitchConnections(),
            this::acceptConnectionFromSwitch);
    return channel.getLocalAddress();
  }

  ImmutableList<Context> contexts() {
    return ImmutableList.copyOf(contexts.values());
  }

  public Function<Handler, Handler.Callbacks> callbackFactoryForNewSwitchConnections() {
    return switchHandler -> {
      Context context = new Context(switchHandler);
      contexts.put(switchHandler, context);
      return new Handler.Callbacks(
          new BufferingConsumer(
              new OFMessageParsingConsumer(
                  new InjectingConsumer<>(this::receivedFromSwitch, context)),
              OFMessageChunker.INSTANCE),
          throwable -> this.switchDisconnected(throwable, context));
    };
  }

  public void acceptConnectionFromSwitch(Handler switchHandler) {
    try {
      log.info("switch connected from {}", switchHandler.remoteAddress());
      log.info("establishing new connection with controller");
      reactor.establish(upstreamAddress, callbackFactoryForNewControllerConnections(switchHandler));
    } catch (IOException e) {
      log.error("couldn't establish a connection with controller", e);
      switchHandler.close();
    }
  }

  private Function<Handler, Handler.Callbacks> callbackFactoryForNewControllerConnections(
      Handler switchHandler) {
    return controllerHandler -> {
      Context context = contexts.get(switchHandler);
      context.setControllerHandler(controllerHandler);
      return new Handler.Callbacks(
          new BufferingConsumer(
              new OFMessageParsingConsumer(
                  new InjectingConsumer<>(this::receivedFromController, context)),
              OFMessageChunker.INSTANCE),
          throwable -> controllerDisconnected(throwable, context));
    };
  }

  private void receivedFromSwitch(OFMessage message, Context context) {
    context.factory = OFFactories.getFactory(message.getVersion());
    switch (message.getType()) {
      case PACKET_IN:
        handlePacketInFromSwitch((OFPacketIn) message, context);
        break;
      case FEATURES_REPLY:
        OFFeaturesReply features = (OFFeaturesReply) message;
        context.datapathId = features.getDatapathId();
        context.switchPorts =
            features
                .getPorts()
                .stream()
                .map(OFPortDesc::getPortNo)
                .filter(p -> !p.equals(OFPort.LOCAL))
                .collect(Collectors.toSet());
        context.controllerConnection().accept(message);
        break;
      default:
        context.controllerConnection().accept(message);
    }
  }

  private void handlePacketInFromSwitch(OFPacketIn message, Context context) {
    if (!context.isExtensionSwitch) {
      context.controllerConnection().accept(message);
      return;
    }
    OFPacketIn originalPktIn = message;
    if (!context.extensionPorts.contains(originalPktIn.getInPort())) {
      context.controllerConnection().accept(originalPktIn);
      return;
    }
    short originalInPort = extractVlanIdFromPayload(originalPktIn);
    OFPacketIn newPktIn =
        originalPktIn
            .createBuilder()
            .setData(withoutVlanHeader(originalPktIn.getData()))
            .setBufferId(OFBufferId.NO_BUFFER) // TODO can we re-use extension switch buffer?
            .setInPort(OFPort.ofShort(originalInPort))
            .build();
    log.info(
        "changed in_port of packet_in message from {} to {} and rerouted it through delegation switch controller connection",
        originalPktIn.getInPort(),
        newPktIn.getInPort());
    context.delegationSwitchContext.controllerConnection().accept(newPktIn);
  }

  private short extractVlanIdFromPayload(OFPacketIn message) {
    byte[] payload = message.getData();
    Ethernet frame = new Ethernet();
    frame.deserialize(payload, 0, payload.length);
    return frame.getVlanID();
  }

  private byte[] withoutVlanHeader(byte[] vlanFrame) {
    Ethernet frame = new Ethernet();
    frame.deserialize(vlanFrame, 0, vlanFrame.length);
    frame.setVlanID(Ethernet.VLAN_UNTAGGED);
    return frame.serialize();
  }

  private void receivedFromController(OFMessage message, Context context) {
    context.factory = OFFactories.getFactory(message.getVersion());
    if (!context.isDelegationSwitch) {
      context.switchConnection.accept(message);
      return;
    }
    switch (message.getType()) {
      case PACKET_OUT:
        handlePacketOutFromControllerToDelegationSwitch((OFPacketOut) message, context);
        break;
      case FLOW_MOD:
        handleFlowModFromControllerToDelegationSwitch((OFFlowMod) message, context);
        break;
      default:
        context.switchConnection.accept(message);
    }
  }

  private void handlePacketOutFromControllerToDelegationSwitch(
      OFPacketOut originalPacketOut, Context context) {
    if (!originalPacketOut.getBufferId().equals(OFBufferId.NO_BUFFER)) {
      // TODO can we re-use buffered packets at extension switch?
      context.switchConnection.accept(originalPacketOut);
      return;
    }
    OFActionOutput originalOutputAction = extractOnlyOutputAction(originalPacketOut.getActions());
    List<OFAction> newActions = new ArrayList<>(originalPacketOut.getActions());
    newActions.remove(originalOutputAction);
    newActions.add(originalOutputAction.createBuilder().setPort(OFPort.IN_PORT).build());

    short vLanId = mapToVlanId(originalOutputAction.getPort());
    byte[] newPayload = createVlanFrame(vLanId, originalPacketOut.getData());

    OFPacketOut newPktOut =
        originalPacketOut
            .createBuilder()
            .setActions(newActions)
            .setData(newPayload)
            .setInPort(context.extensionPort)
            .build();
    log.info(
        "changing and rerouting packet_out which the upstream controller intended for the delegation switch to extension switch.\nold: {}\nnew: {}",
        originalPacketOut,
        newPktOut);
    context.extensionSwitchContext.switchConnection.accept(newPktOut);
  }

  OFActionOutput extractOnlyOutputAction(List<OFAction> actions) {
    return actions
        .stream()
        .filter(a -> a instanceof OFActionOutput)
        .map(a -> (OFActionOutput) a)
        .collect(MoreCollectors.onlyElement());
  }

  static short mapToVlanId(OFPort port) {
    return port.equals(OFPort.FLOOD) ? FLOOD_VLAN_ID : port.getShortPortNumber();
  }

  private byte[] createVlanFrame(short vlanId, byte[] payload) {
    Ethernet frame = new Ethernet();
    frame.deserialize(payload, 0, payload.length);
    frame.setVlanID(vlanId);
    return frame.serialize();
  }

  private void handleFlowModFromControllerToDelegationSwitch(
      OFFlowMod originalFlowMod, Context context) {
    OFActionOutput originalOutputAction = extractOnlyOutputAction(originalFlowMod.getActions());

    if (originalFlowMod
        .getActions()
        .stream()
        .filter(
            a -> a.getType() == OFActionType.SET_VLAN_VID || a.getType() == OFActionType.STRIP_VLAN)
        .findAny()
        .isPresent()) {
      // can't install flow rule in extension switch that modifies VLAN tag, as we need that for internal use. Send error
      log.warn("discarding flow-mod message, can't handle this: {}", originalFlowMod);
      // TODO: send error message to controller
      return;
    }

    List<OFAction> newActions = new ArrayList<>(originalFlowMod.getActions());
    newActions.remove(originalOutputAction);

    short vlanId = mapToVlanId(originalOutputAction.getPort());
    OFAction vlanAction = context.factory.actions().setVlanVid(VlanVid.ofVlan(vlanId));
    OFActionOutput newOutputAction =
        originalOutputAction.createBuilder().setPort(OFPort.IN_PORT).build();
    newActions.add(vlanAction);
    newActions.add(newOutputAction);
    Match newMatch =
        originalFlowMod
            .getMatch()
            .createBuilder()
            .setExact(MatchField.IN_PORT, context.extensionPort)
            .build();
    OFFlowMod newFlowMod =
        originalFlowMod.createBuilder().setActions(newActions).setMatch(newMatch).build();
    context.extensionSwitchContext.switchConnection.accept(newFlowMod);
  }

  private void switchDisconnected(Throwable cause, Context context) {
    log.info(
        "switch connection broke (remote address: {}); cause: {}",
        context.switchHandler.remoteAddress(),
        cause == null ? "disconnected" : cause.getMessage());
    contexts.remove(context.switchHandler);
    context.controllerHandler().close();
  }

  private void controllerDisconnected(Throwable cause, Context context) {
    SocketAddress localAddress = context.controllerHandler.localAddress();
    log.info(
        "controller connection broke (local address: {}); cause: {}",
        localAddress == null ? "not connected yet" : localAddress,
        cause == null ? "disconnected" : cause.getMessage());
    context.switchHandler.close();
  }

  static class Context {

    OFFactory factory;
    final Handler switchHandler;
    final Consumer<OFMessage> switchConnection;
    final Evictor evictor = new Evictor(this);
    private Handler controllerHandler;
    private Consumer<OFMessage> controllerConnection;
    DatapathId datapathId;
    Set<OFPort> switchPorts;
    Set<OFPort> extensionPorts = new HashSet<>();
    Context delegationSwitchContext; // TODO map extensionPort -> delegationswitch context
    Context extensionSwitchContext;

    boolean isExtensionSwitch = false;

    boolean isDelegationSwitch = false;
    // when this is the context of a delegation switch, this value represents the extension port of the switch we delegate to
    OFPort extensionPort;

    Context(Handler switchHandler) {
      this.switchHandler = switchHandler;
      this.switchConnection = new OFMessageSerializingConsumer(bytes -> switchHandler.send(bytes, null));
    }

    void setControllerHandler(Handler controllerHandler) {
      this.controllerHandler = controllerHandler;
      this.controllerConnection =
          new OFMessageSerializingConsumer(bytes -> controllerHandler.send(bytes, null));
    }

    Handler controllerHandler() {
      return controllerHandler;
    }

    Consumer<OFMessage> controllerConnection() {
      return controllerConnection;
    }
  }
}
