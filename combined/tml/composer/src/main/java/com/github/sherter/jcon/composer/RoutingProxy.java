package com.github.sherter.jcon.composer;

import com.github.sherter.jcon.InjectingConsumer;
import com.github.sherter.jcon.networking.Handler;
import com.github.sherter.jcon.networking.Reactor;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class RoutingProxy {

  private static final Logger log = LoggerFactory.getLogger(RoutingProxy.class);

  final Reactor reactor;

  private final ConcurrentHashMap<Handler, Context> contexts = new ConcurrentHashMap<>();

  private final Function<Handler, Handler.Callbacks> callbackFactory =
      handler -> {
        Context context = new Context(handler);
        contexts.put(handler, context);
        return new Handler.Callbacks(
            new InjectingConsumer<>(this::receivedFromDownstream, context),
            throwable -> this.downstreamDisconnected(throwable, context));
      };

  private final Object upstreamConnectionLock = new Object();
  private InetSocketAddress upstreamAddress;
  private InetSocketAddress listenAddress;

  private RoutingProxy(Reactor reactor) {
    this.reactor = reactor;
  }

  static RoutingProxy listenOn(Reactor reactor, @Nullable InetSocketAddress listenAddress)
      throws IOException {
    RoutingProxy proxy = new RoutingProxy(reactor);
    ServerSocketChannel channel =
        reactor.listen(listenAddress, proxy.callbackFactory, proxy::acceptConnection);
    proxy.listenAddress =
        new InetSocketAddress(InetAddress.getLoopbackAddress(), channel.socket().getLocalPort());
    return proxy;
  }

  static RoutingProxy listenOn(Reactor reactor, int port) throws IOException {
    RoutingProxy proxy = new RoutingProxy(reactor);
    ServerSocketChannel channel =
        reactor.listen(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
            proxy.callbackFactory,
            proxy::acceptConnection);
    proxy.listenAddress =
        new InetSocketAddress(channel.socket().getInetAddress(), channel.socket().getLocalPort());
    return proxy;
  }

  /**
   * Creates a new proxy registered with the given reactor and listening on a random port on the
   * loopback address.
   */
  static RoutingProxy listenOnLoopback(Reactor reactor) throws IOException {
    RoutingProxy proxy = new RoutingProxy(reactor);
    ServerSocketChannel channel =
        reactor.listen(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
            proxy.callbackFactory,
            proxy::acceptConnection);
    proxy.listenAddress = (InetSocketAddress) channel.socket().getLocalSocketAddress();
    return proxy;
  }

  InetSocketAddress listenAddress() {
    return listenAddress;
  }

  private void acceptConnection(Handler handler) {
    //log.info("accepting connection from {}", handler.remoteAddress());
    synchronized (upstreamConnectionLock) {
      if (upstreamAddress == null) {
        handler.close();
        return;
      }
      if (!contexts.containsKey(handler)) {
        //log.info("contexts don't contain {}", handler);
        handler.close();
        return;
      }
      try {
        //log.info("establishing connection to {}", upstreamAddress);
        reactor.establish(upstreamAddress, upstreamCallbackFactory(handler));
      } catch (IOException e) {
        handler.close();
      }
    }
  }

  private Function<Handler, Handler.Callbacks> upstreamCallbackFactory(Handler downstreamHandler) {
    return upstreamHandler -> {
      Context context = contexts.get(downstreamHandler);
      context.upstreamHandler = upstreamHandler;
      return new Handler.Callbacks(
          new InjectingConsumer<>(this::receivedFromUpstream, context),
          throwable -> upstreamDisconnected(throwable, context));
    };
  }

  private void receivedFromDownstream(ByteBuffer data, Context context) {
    // log.info("switch -> controller: {}", Arrays.toString(bytes));
    context.upstreamHandler.send(data, context.downstreamHandler);
  }

  private void downstreamDisconnected(Throwable cause, Context context) {
    // log.info("disconnected {}", context.downstreamHandler.remoteAddress());
    contexts.remove(context.downstreamHandler);
    if (context.upstreamHandler != null) {
      context.upstreamHandler.close();
    }
  }

  private void receivedFromUpstream(ByteBuffer data, Context context) {
    // log.info("controller -> switch: {}", Arrays.toString(bytes));
    context.downstreamHandler.send(data, context.upstreamHandler);
  }

  private void upstreamDisconnected(Throwable cause, Context context) {
    // log.info("disconnected {}", context.upstreamHandler.remoteAddress());
    context.downstreamHandler.close();
  }

  void connectTo(@Nullable InetSocketAddress upstreamListenAddress) {
    InetSocketAddress connectToAddress;
    if (upstreamListenAddress.getAddress().isAnyLocalAddress()) {
      connectToAddress =
          new InetSocketAddress(InetAddress.getLoopbackAddress(), upstreamListenAddress.getPort());
    } else {
      connectToAddress = upstreamListenAddress;
    }
    synchronized (upstreamConnectionLock) {
      this.upstreamAddress = connectToAddress;
      // will also close upstream handlers, see downstreamDisconnected callback
      contexts.values().stream().map(c -> c.downstreamHandler).forEach(h -> h.close());
      contexts.clear();
    }
  }

  static class Context {
    final Handler downstreamHandler;
    Handler upstreamHandler;

    Context(Handler downstreamHandler) {
      this.downstreamHandler = downstreamHandler;
    }
  }
}
