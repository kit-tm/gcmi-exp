package com.github.sherter.jcon.composer;

import com.github.sherter.jcon.InterceptableForwarder;
import com.github.sherter.jcon.examples.graphcomputation.GraphComputation;
import com.github.sherter.jcon.networking.Reactor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;

class InterceptableForwarderLayer implements Layer {

  private final InterceptableForwarder forwarder;
  private final String type;
  private final SocketAddress localAddress;

  InterceptableForwarderLayer(
      InterceptableForwarder forwarder, String type, SocketAddress localAddress) {
    this.forwarder = forwarder;
    this.type = type;
    this.localAddress = localAddress;
  }

  static Layer create(String type) throws IOException {
    Selector selector = Selector.open();
    Reactor reactor = new Reactor(selector);
    InterceptableForwarder.Builder builder = new InterceptableForwarder.Builder(reactor, null);
    switch (type) {
      case "forward":
        // don't register anything
        break;
      case "port_changer":
        new PacketOutPortChanger().registerWith(builder);
        break;
      default:
        throw new RuntimeException("Unkknown type: " + type);
    }
    InterceptableForwarder forwarder = builder.build();
    ServerSocketChannel listenSocket =
        reactor.listen(
            null,
            forwarder.callbackFactoryForNewSwitchConnections(),
            forwarder::acceptConnectionFromSwitch);

    Thread thread = new Thread(() -> reactor.loop());
    thread.start();
    return new InterceptableForwarderLayer(forwarder, type, listenSocket.getLocalAddress());
  }

  @Override
  public void destroy() {}

  @Override
  public InetSocketAddress listenAddress() {
    return (InetSocketAddress) localAddress;
  }

  @Override
  public void connectTo(List<InetSocketAddress> addresses) {
    checkArgument(addresses.size() == 1);
    forwarder.connectTo(addresses.get(0));
  }

  @Override
  public String type() {
    return type;
  }
}
