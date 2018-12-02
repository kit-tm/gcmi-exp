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
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.projectfloodlight.openflow.protocol.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InterceptableMultiForwarder {

  private static final Logger log = LoggerFactory.getLogger(InterceptableForwarder.class);

  private final Reactor reactor;
  private final InetSocketAddress upstreamAddress;
  private final ImmutableMap<OFType, BiConsumer<Object, Context>> downstreamCallbacks;
  private final ImmutableMap<OFType, BiConsumer<Object, Context>> upstreamCallbacks;
  private final Consumer<Context> switchCallback;
  private final BiConsumer<Context, Object> controllerCallback;

  InterceptableMultiForwarder(
      Reactor reactor,
      InetSocketAddress upstreamAddress,
      Map<OFType, BiConsumer<Object, Context>> downstreamCallbacks,
      Map<OFType, BiConsumer<Object, Context>> upstreamCallbacks,
      Consumer<Context> switchCallback,
      BiConsumer<Context, Object> controllerCallback) {
    this.reactor = reactor;
    this.upstreamAddress = upstreamAddress;
    this.downstreamCallbacks = ImmutableMap.copyOf(downstreamCallbacks);
    this.upstreamCallbacks = ImmutableMap.copyOf(upstreamCallbacks);
    this.switchCallback = switchCallback;
    this.controllerCallback = controllerCallback;
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

  public Function<Handler, Handler.Callbacks> callbackFactoryForNewSwitchConnections() {
    return switchHandler -> {
      Context context = new Context(switchHandler);
      switchCallback.accept(context);
      return new Handler.Callbacks(
          new BufferingConsumer(
              new OFMessageParsingConsumer(
                  new InjectingConsumer<>(this::receivedFromSwitch, context)),
              OFMessageChunker.INSTANCE),
          throwable -> this.switchDisconnected(throwable, context));
    };
  }

  private Function<Handler, Handler.Callbacks> callbackFactoryForNewControllerConnections(Object ret) {
    return controllerHandler -> {
      Context context = new Context(controllerHandler);
      controllerCallback.accept(context, ret);
      return new Handler.Callbacks(
          new BufferingConsumer(
              new OFMessageParsingConsumer(
                  new InjectingConsumer<>(this::receivedFromController, context)),
              OFMessageChunker.INSTANCE),
          throwable -> controllerDisconnected(throwable, context));
    };
  }

  @VisibleForTesting
  void receivedFromSwitch(OFMessage message, Context context) {
    message = context.toInternal(message);

    BiConsumer<Object, Context> callback = upstreamCallbacks.get(message.getType());
    if (callback != null) {
      try {
        callback.accept(message, context);
      } catch (Exception e) {
        e.printStackTrace();
        throw e;
      }
    } else {
      log.info("discarding unhandled upstream message: {}", message.getType());
    }
  }

  @VisibleForTesting
  void receivedFromController(OFMessage message, Context context) {
    message = context.toInternal(message);

    BiConsumer<Object, Context> callback = downstreamCallbacks.get(message.getType());
    if (callback != null) {
      try {
        callback.accept(message, context);
      } catch (Exception e) {
        e.printStackTrace();
        throw e;
      }
    } else {
      log.info("discarding unhandled downstream message: {}", message.getType());
    }
  }

  private void switchDisconnected(
    Throwable throwable, Context connectionPairContext) {}

  private void controllerDisconnected(
    Throwable throwable, Context context) {}

  public void acceptConnectionFromSwitch(Handler switchHandler) {
    log.info("switch connected from {}", switchHandler.remoteAddress());
  }

  public void establishControllerConnection(Object ret) {
    try {
      reactor.establish(upstreamAddress, callbackFactoryForNewControllerConnections(ret));
    } catch (IOException e) {
      log.error("couldn't establish a connection with controller", e);
      //switchHandler.close();
    }
  }

  public static class Context {
    private final SingleXidManager xidManager = new SingleXidManager();
    private final Handler handler;
    private final OFMessageSerializingConsumer sender;

    public Context(Handler handler) {
      this.handler = handler;
      this.sender = new OFMessageSerializingConsumer(bytes -> handler.send(bytes));
    }

    public Handler handler() {
      return handler;
    }

    public void send(OFMessage message) {
      OFMessage actualMessage = xidManager.fromInternal(message);
      sender.accept(actualMessage);
    }

    public OFMessage toInternal(OFMessage message) {
      return xidManager.toInternal(message);
    }

  }

  public static class Builder {
    private final Reactor reactor;
    private final InetSocketAddress upstreamAddress;
    private final Consumer<Context> switchCallback;
    private final BiConsumer<Context, Object> controllerCallback;
    private final Map<OFType, BiConsumer<Object, Context>> downstreamCallbacks = new HashMap<>();
    private final Map<OFType, BiConsumer<Object, Context>> upstreamCallbacks = new HashMap<>();

    /**
     * @param upstreamAddress required builder parameter: address to connect to build a connection
     *     pair when a switch connects
     */
    public Builder(Reactor reactor, InetSocketAddress upstreamAddress, Consumer<Context> switchCallback, BiConsumer<Context, Object> controllerCallback) {
      this.reactor = reactor;
      this.upstreamAddress = upstreamAddress;
      this.switchCallback = switchCallback;
      this.controllerCallback = controllerCallback;
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

    public InterceptableMultiForwarder build() {
      return new InterceptableMultiForwarder(
          reactor, upstreamAddress, downstreamCallbacks, upstreamCallbacks, switchCallback, controllerCallback);
    }

    /** Call {@link Reactor#loop()} on returned value to get this going. */
    public InterceptableMultiForwarder buildAndListen(Reactor reactor, InetSocketAddress listenAddress)
        throws IOException {
          InterceptableMultiForwarder forwarder =
          new InterceptableMultiForwarder(
              reactor, upstreamAddress, downstreamCallbacks, upstreamCallbacks, switchCallback, controllerCallback);
      reactor.listen(
          listenAddress,
          forwarder.callbackFactoryForNewSwitchConnections(),
          forwarder::acceptConnectionFromSwitch);
      return forwarder;
    }
  }
}
