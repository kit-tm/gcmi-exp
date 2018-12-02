package com.github.sherter.jcon.examples.logandforward;

import com.github.sherter.jcon.BufferingConsumer;
import com.github.sherter.jcon.InjectingConsumer;
import com.github.sherter.jcon.OFMessageChunker;
import com.github.sherter.jcon.OFMessageParsingConsumer;
import com.github.sherter.jcon.OFMessageSerializingConsumer;
import com.github.sherter.jcon.networking.Handler;
import com.github.sherter.jcon.networking.Reactor;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LogAndForward {

  private static final Logger log = LoggerFactory.getLogger(LogAndForward.class);
  private final SocketAddress upstreamAddress;
  private final Reactor reactor;
  private final Map<Handler, Context> contexts = new HashMap<>();

  public LogAndForward(SocketAddress upstreamAddress, Reactor reactor) {
    this.upstreamAddress = upstreamAddress;
    this.reactor = reactor;
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
    log.info("switch -> controller: {}", message);
    context.controllerConnection().accept(message);
  }

  private void receivedFromController(OFMessage message, Context context) {
    log.info("controller -> switch: {}", message);
    context.switchConnection().accept(message);
  }

  private void switchDisconnected(Throwable cause, Context context) {
    log.info(
        "switch connection broke (remote address: {}); cause: {}",
        context.switchHandler.remoteAddress(),
        cause == null ? "disconnected" : cause.getMessage());
    contexts.remove(context.switchHandler());
    context.controllerHandler().close();
  }

  private void controllerDisconnected(Throwable cause, Context context) {
    SocketAddress localAddress = context.controllerHandler.localAddress();
    log.info(
        "controller connection broke (local address: {}); cause: {}",
        localAddress == null ? "not connected yet" : localAddress,
        cause == null ? "disconnected" : cause.getMessage());
    context.switchHandler().close();
  }

  static class Context {
    private final Handler switchHandler;
    private Consumer<OFMessage> switchConnection;
    private Handler controllerHandler;
    private Consumer<OFMessage> controllerConnection;

    Context(Handler switchHandler) {
      this.switchHandler = switchHandler;
      this.switchConnection =
          new OFMessageSerializingConsumer(bytes -> switchHandler.send(bytes, null));
    }

    Handler switchHandler() {
      return switchHandler;
    }

    Consumer<OFMessage> switchConnection() {
      return switchConnection;
    }

    void setControllerHandler(Handler controllerHandler) {
      this.controllerHandler = controllerHandler;
      this.controllerConnection =
          new OFMessageSerializingConsumer(bytes -> controllerHandler.send(bytes, switchHandler));
      this.switchConnection =
          new OFMessageSerializingConsumer(bytes -> switchHandler.send(bytes, controllerHandler));
    }

    Handler controllerHandler() {
      return controllerHandler;
    }

    Consumer<OFMessage> controllerConnection() {
      return controllerConnection;
    }
  }
}
