package com.github.sherter.jcon.composer;

import static com.google.common.base.Preconditions.checkArgument;

import com.github.sherter.jcon.examples.pbce.Pbce;
import com.github.sherter.jcon.examples.pbce.TelnetServer;
import com.github.sherter.jcon.networking.Reactor;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.Selector;
import java.util.List;

class PbceLayer implements Layer {

  private final Reactor reactor;
  private final RoutingProxy proxy;
  private final LayerService layerService;
  private final InetSocketAddress telnet;
  private final Thread thread;
  private final SocketAddress listenAddress;
  private Thread t;
  private boolean isRunning = false;

  private final Object lock = new Object();

  private PbceLayer(
      Reactor reactor,
      RoutingProxy proxy,
      LayerService layerService,
      InetSocketAddress telnet,
      Thread thread,
      SocketAddress listenAddress) {
    this.reactor = reactor;
    this.proxy = proxy;
    this.layerService = layerService;
    this.telnet = telnet;
    this.thread = thread;
    this.listenAddress = listenAddress;
  }

  static PbceLayer create(LayerService layerService) throws IOException {
    Selector selector = Selector.open();
    Reactor reactor = new Reactor(selector);
    RoutingProxy proxy = RoutingProxy.listenOnLoopback(reactor);
    Pbce pbce = new Pbce(proxy.listenAddress(), reactor);
    SocketAddress listenAddress = pbce.listenOn(null);
    TelnetServer telnet = new TelnetServer(pbce);
    InetSocketAddress telnetListenAddress = telnet.listenOn(reactor, new InetSocketAddress("0.0.0.0", 50000));
    Thread thread = new Thread(() -> reactor.loop());
    thread.start();
    return new PbceLayer(reactor, proxy, layerService, telnetListenAddress, thread, listenAddress);
  }

  @Override
  public String toString() {
    return "PBCE";
  }

  @Override
  public InetSocketAddress listenAddress() {
    return (InetSocketAddress) listenAddress;
  }

  @Override
  public void connectTo(List<InetSocketAddress> listenAddresses) {
    checkArgument(listenAddresses.size() <= 1);

    if (listenAddresses.size() == 0) {
      proxy.connectTo(null);
    } else {
      proxy.connectTo(listenAddresses.get(0));
    }
  }

  @Override
  public Configuration configuration() {
    return new Configuration(telnet);
  }

  @Override
  public String type() {
    return "pbce";
  }

  @Override
  public void destroy() {
    reactor.close();
    layerService.remove(this);
  }

  static class Configuration {
    private InetSocketAddress telnet;

    Configuration() {}

    Configuration(InetSocketAddress telnetServerListenAddress) {
      this.telnet = telnetServerListenAddress;
    }

    public InetSocketAddress getTelnet() {
      return telnet;
    }

    public void setTelnet(InetSocketAddress telnet) {
      this.telnet = telnet;
    }
  }
}
