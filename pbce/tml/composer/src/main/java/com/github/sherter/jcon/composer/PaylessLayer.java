package com.github.sherter.jcon.composer;

import com.github.sherter.jcon.InterceptableForwarder;
import com.github.sherter.jcon.examples.payless.Payless;
import com.github.sherter.jcon.networking.Reactor;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.Selector;
import java.util.List;
import org.projectfloodlight.openflow.protocol.OFStatsReply;
import org.projectfloodlight.openflow.protocol.OFStatsRequest;

public class PaylessLayer implements Layer {

  private final Reactor reactor;
  private final RoutingProxy proxy;
  private final InetSocketAddress listenAddress;
  private final Thread thread;
  private final LayerService service;

  private final Object lock = new Object();

  public PaylessLayer(
      Reactor reactor,
      RoutingProxy proxy,
      InetSocketAddress listenAddress,
      Thread thread,
      LayerService service) {
    this.reactor = reactor;
    this.proxy = proxy;
    this.listenAddress = listenAddress;
    this.thread = thread;
    this.service = service;
  }

  static PaylessLayer create(LayerService service) throws IOException {
    Reactor reactor = new Reactor(Selector.open());
    RoutingProxy proxy = RoutingProxy.listenOn(reactor, null);

    Payless payless = new Payless();

    InterceptableForwarder layer =
        new InterceptableForwarder.Builder(reactor, proxy.listenAddress())
            .interceptDownstream(OFStatsRequest.class, payless::receiveFromController)
            .interceptUpstream(OFStatsReply.class, payless::receiveFromSwitch)
            .build();
    InetSocketAddress listenAddress =
        layer.listenOn(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    Thread thread = new Thread(() -> reactor.loop());
    thread.start();
    return new PaylessLayer(reactor, proxy, listenAddress, thread, service);
  }

  @Override
  public void destroy() {
    synchronized (lock) {
      thread.interrupt();
      try {
        thread.join();
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
      reactor.close();
      service.remove(this);
    }
  }

  @Override
  public InetSocketAddress listenAddress() {
    return listenAddress;
  }

  @Override
  public void connectTo(List<InetSocketAddress> upstreamAddresses) {
    if (upstreamAddresses.size() > 1) {
      throw new RuntimeException("only supports one upstream controller");
    }
    if (upstreamAddresses.size() == 0) {
      proxy.connectTo(null);
    } else {
      proxy.connectTo(upstreamAddresses.get(0));
    }
  }

  @Override
  public String type() {
    return "payless";
  }
}
