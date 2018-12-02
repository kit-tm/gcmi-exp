package com.github.sherter.jcon.composer;

import com.github.sherter.jcon.examples.logandforward.LogAndForward;
import com.github.sherter.jcon.networking.Reactor;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.util.List;

class LogAndForwardLayer implements Layer {

  private final Reactor reactor;
  private final LogAndForward logAndForward;
  private final ServerSocketChannel channel;
  private final RoutingProxy proxy;
  private final LayerService service;

  private boolean isRunning = false;
  private final Thread t;
  private final Object lock = new Object();

  private LogAndForwardLayer(
      Reactor reactor,
      LogAndForward logAndForward,
      ServerSocketChannel channel,
      RoutingProxy proxy,
      LayerService service,
      Thread t) {
    this.reactor = reactor;
    this.logAndForward = logAndForward;
    this.channel = channel;
    this.proxy = proxy;
    this.service = service;
    this.t = t;
  }

  static LogAndForwardLayer create(LayerService service) throws IOException {
    Reactor reactor = new Reactor(Selector.open());
    RoutingProxy proxy = RoutingProxy.listenOnLoopback(reactor);
    LogAndForward pbce = new LogAndForward(proxy.listenAddress(), reactor);
    ServerSocketChannel channel =
        reactor.listen(
            null, pbce.callbackFactoryForNewSwitchConnections(), pbce::acceptConnectionFromSwitch);
    Thread t = new Thread(() -> reactor.loop());
    t.start();
    return new LogAndForwardLayer(reactor, pbce, channel, proxy, service, t);
  }

  @Override
  public void destroy() {
    t.interrupt();
    try {
      t.join();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
    reactor.close();
    service.remove(this);
  }

  @Override
  public InetSocketAddress listenAddress() {
    return new InetSocketAddress(
        channel.socket().getInetAddress(), channel.socket().getLocalPort());
  }

  @Override
  public void connectTo(List<InetSocketAddress> newUpstreamAddress) {
    if (newUpstreamAddress.size() > 1) {
      throw new RuntimeException("only supports one upstream controller");
    }
    proxy.connectTo(newUpstreamAddress.get(0));
  }

  @Override
  public String type() {
    return "log_and_forward";
  }
}
