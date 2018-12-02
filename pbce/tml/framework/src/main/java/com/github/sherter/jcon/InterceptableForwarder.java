package com.github.sherter.jcon;

import static com.github.sherter.jcon.Utils.DOWNSTREAM_CLASSES;
import static com.github.sherter.jcon.Utils.MESSAGE_TYPE_CLASSES;
import static com.github.sherter.jcon.Utils.UPSTREAM_CLASSES;

import com.github.sherter.jcon.networking.Handler;
import com.github.sherter.jcon.networking.Reactor;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.projectfloodlight.openflow.protocol.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InterceptableForwarder {

  private static final Logger log = LoggerFactory.getLogger(InterceptableForwarder.class);

  private final Reactor reactor;
  private final InetSocketAddress upstreamAddress;
  private final ImmutableMap<OFType, BiConsumer<Object, Context>> downstreamCallbacks;
  private final ImmutableMap<OFType, BiConsumer<Object, Context>> upstreamCallbacks;

  private final Map<Handler, ConnectionPairContext> handlerContexts = new HashMap<>();
  private final Map<ConnectionPairContext, Context> contexts = new HashMap<>();

  InterceptableForwarder(
      Reactor reactor,
      InetSocketAddress upstreamAddress,
      Map<OFType, BiConsumer<Object, Context>> downstreamCallbacks,
      Map<OFType, BiConsumer<Object, Context>> upstreamCallbacks) {
    this.reactor = reactor;
    this.upstreamAddress = upstreamAddress;
    this.downstreamCallbacks = ImmutableMap.copyOf(downstreamCallbacks);
    this.upstreamCallbacks = ImmutableMap.copyOf(upstreamCallbacks);
  }

  public InetSocketAddress listenOn(@Nullable InetSocketAddress listenAddress) throws IOException {
    ServerSocketChannel channel =
        reactor.listen(
            listenAddress,
            callbackFactoryForNewSwitchConnections(),
            this::acceptConnectionFromSwitch);
    return new InetSocketAddress(
        channel.socket().getInetAddress(), channel.socket().getLocalPort());
  }

  // argument to reactor.listen()
  public Function<Handler, Handler.Callbacks> callbackFactoryForNewSwitchConnections() {
    return switchHandler -> {
      ConnectionPairContext connectionPairContext = new ConnectionPairContext(switchHandler);
      handlerContexts.put(switchHandler, connectionPairContext);
      contexts.put(connectionPairContext, new Context(connectionPairContext));
      return new Handler.Callbacks(
          new BufferingConsumer(
              new OFMessageParsingConsumer(
                  new InjectingConsumer<>(this::receivedFromSwitch, connectionPairContext)),
              OFMessageChunker.INSTANCE),
          throwable -> this.switchDisconnected(throwable, connectionPairContext));
    };
  }

  @VisibleForTesting
  void receivedFromSwitch(OFMessage message, ConnectionPairContext connectionPairContext) {
    XidManager xid = connectionPairContext.xidManager();
    message = xid.toInternalFromDownstream(message);

    BiConsumer<Object, Context> callback = upstreamCallbacks.get(message.getType());
    if (callback != null) {
      try {
        callback.accept(message, contexts.get(connectionPairContext));
      } catch (Exception e) {
        e.printStackTrace();
        throw e;
      }    
    } else {
      connectionPairContext.sendToController(message);
    }
  }

  private void switchDisconnected(
      Throwable throwable, ConnectionPairContext connectionPairContext) {}

  // argument to reactor.listen()
  public void acceptConnectionFromSwitch(Handler switchHandler) {
    log.info("switch connected from {}", switchHandler.remoteAddress());
    log.info("establishing new connection with controller");
    try {
      reactor.establish(upstreamAddress, callbackFactoryForNewControllerConnections(switchHandler));
    } catch (IOException e) {
      log.error("couldn't establish a connection with controller", e);
      switchHandler.close();
    }
  }

  private Function<Handler, Handler.Callbacks> callbackFactoryForNewControllerConnections(
      Handler switchHandler) {
    return controllerHandler -> {
      ConnectionPairContext connectionPairContext = handlerContexts.get(switchHandler);
      connectionPairContext.setControllerHandler(controllerHandler);
      return new Handler.Callbacks(
          new BufferingConsumer(
              new OFMessageParsingConsumer(
                  new InjectingConsumer<>(this::receivedFromController, connectionPairContext)),
              OFMessageChunker.INSTANCE),
          throwable -> controllerDisconnected(throwable, connectionPairContext));
    };
  }

  @VisibleForTesting
  void receivedFromController(OFMessage message, ConnectionPairContext connectionPairContext) {
    XidManager xid = connectionPairContext.xidManager();
    message = xid.toInternalFromUpstream(message);

    BiConsumer<Object, Context> callback = downstreamCallbacks.get(message.getType());
    if (callback != null) {
      try {
        callback.accept(message, contexts.get(connectionPairContext));
      } catch (Exception e) {
        e.printStackTrace();
        throw e;
      }
    } else {

      connectionPairContext.sendToSwitch(message);
      // handle message automatically (forward it)
    }
  }

  private void controllerDisconnected(
      Throwable throwable, ConnectionPairContext connectionPairContext) {}

  public static class Context {
    private final ConnectionPairContext connectionPairContext;

    /**
     * If the received message is a reply for a message previously sent, this field contains the
     * original request message
     */
    Context(ConnectionPairContext connectionPairContext) {
      this.connectionPairContext = connectionPairContext;
    }

    public void sendDownstream(OFMessage message) {
      connectionPairContext.sendToSwitch(message);
    }

    public void sendUpstream(OFMessage message) {
      connectionPairContext.sendToController(message);
    }

    public long getNewID() {
      return connectionPairContext.xidManager().newOriginXid();
    }
  }

  static class ConnectionPairContext {
    final Handler switchHandler;
    final Consumer<OFMessage> switchSender;
    Handler controllerHandler;
    Consumer<OFMessage> controllerSender;

    private final XidManager xidManager = new XidManager();

    ConnectionPairContext(Handler switchHandler) {
      this.switchHandler = switchHandler;
      OFMessageSerializingConsumer sender =
          new OFMessageSerializingConsumer(bytes -> switchHandler.send(bytes));
      this.switchSender =
          m -> {
            sender.accept(m);
          };
    }

    void setControllerHandler(Handler controllerHandler) {
      this.controllerHandler = controllerHandler;
      OFMessageSerializingConsumer sender =
          new OFMessageSerializingConsumer(bytes -> controllerHandler.send(bytes));
      this.controllerSender =
          m -> {
            sender.accept(m);
          };
    }

    void sendToSwitch(OFMessage message) {
      OFMessage actualMessage = xidManager.internalToDownstream(message);
      //log.info("Sending to switch: {}", actualMessage);
      switchSender.accept(actualMessage);
    }

    void sendToController(OFMessage message) {
      OFMessage actualMessage = xidManager.internalToUpstream(message);
      //log.info("Sending to controller: {}", actualMessage);
      controllerSender.accept(actualMessage);
    }

    XidManager xidManager() {
      return xidManager;
    }
  }

  public static class Builder {
    private final Reactor reactor;
    private final InetSocketAddress upstreamAddress;
    private final Map<OFType, BiConsumer<Object, Context>> downstreamCallbacks = new HashMap<>();
    private final Map<OFType, BiConsumer<Object, Context>> upstreamCallbacks = new HashMap<>();

    /**
     * @param upstreamAddress required builder parameter: address to connect to build a connection
     *     pair when a switch connects
     */
    public Builder(Reactor reactor, InetSocketAddress upstreamAddress) {
      this.reactor = reactor;
      this.upstreamAddress = upstreamAddress;
    }

    public <T extends OFMessage> Builder interceptDownstream(
        Class<T> clazz, BiConsumer<? super T, ? super Context> callback) {
      if (!DOWNSTREAM_CLASSES.contains(clazz)) {
        throw new IllegalArgumentException(
            "Cannot register callback for "
                + clazz.toString()
                + ". Must be one of "
                + DOWNSTREAM_CLASSES);
      }
      downstreamCallbacks.put(
          MESSAGE_TYPE_CLASSES.inverse().get(clazz), (m, c) -> callback.accept(clazz.cast(m), c));
      return this;
    }

    public <T extends OFMessage> Builder interceptUpstream(
        Class<T> clazz, BiConsumer<? super T, ? super Context> callback) {
      if (!UPSTREAM_CLASSES.contains(clazz)) {
        throw new IllegalArgumentException(
            "Cannot register callback for "
                + clazz.toString()
                + ". Must be one of "
                + UPSTREAM_CLASSES);
      }
      upstreamCallbacks.put(
          MESSAGE_TYPE_CLASSES.inverse().get(clazz), (m, c) -> callback.accept(clazz.cast(m), c));
      return this;
    }

    public InterceptableForwarder build() {
      return new InterceptableForwarder(
          reactor, upstreamAddress, downstreamCallbacks, upstreamCallbacks);
    }

    /** Call {@link Reactor#loop()} on returned value to get this going. */
    public InterceptableForwarder buildAndListen(Reactor reactor, InetSocketAddress listenAddress)
        throws IOException {
      InterceptableForwarder forwarder =
          new InterceptableForwarder(
              reactor, upstreamAddress, downstreamCallbacks, upstreamCallbacks);
      reactor.listen(
          listenAddress,
          forwarder.callbackFactoryForNewSwitchConnections(),
          forwarder::acceptConnectionFromSwitch);
      return forwarder;
    }
  }
}
