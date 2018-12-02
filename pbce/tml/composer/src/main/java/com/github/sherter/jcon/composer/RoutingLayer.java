package com.github.sherter.jcon.composer;

import static com.google.common.base.Preconditions.checkArgument;

import com.github.sherter.jcon.networking.Reactor;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.Selector;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RoutingLayer implements Layer {

  private static final Logger log = LoggerFactory.getLogger(RoutingLayer.class);

  private final RoutingProxy proxy;
  private final Thread t;

  public RoutingLayer(RoutingProxy proxy, Thread t) {
    this.proxy = proxy;
    this.t = t;
  }

  static RoutingLayer create(InetSocketAddress listenAddress) throws IOException {
    RoutingProxy proxy = RoutingProxy.listenOn(new Reactor(Selector.open()), listenAddress);
    Thread t = new Thread(() -> proxy.reactor.loop());
    log.info("Starting Routing Layer");
    t.start();
    return new RoutingLayer(proxy, t);
  }

  @Override
  public void destroy() {
    t.interrupt();
    try {
      t.join();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
    proxy.reactor.close();
  }

  @Override
  public InetSocketAddress listenAddress() {
    return proxy.listenAddress();
  }

  @Override
  public void connectTo(List<InetSocketAddress> addresses) {
    log.info("connecting to {}", addresses);
    checkArgument(addresses.size() == 1);
    proxy.connectTo(addresses.get(0));
  }

  @Override
  public String type() {
    return "router";
  }
}
