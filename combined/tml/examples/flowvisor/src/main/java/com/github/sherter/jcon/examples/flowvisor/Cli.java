package com.github.sherter.jcon.examples.flowvisor;

import com.github.sherter.jcon.networking.Reactor;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.Selector;

class Cli {

  public static void main(String[] args) throws IOException {

    Reactor reactor = new Reactor(Selector.open());
    FlowVisor flowVisor = new FlowVisor(reactor);
    reactor.listen(
        new InetSocketAddress("127.0.0.1", 6633),
        flowVisor.callbackFactoryForNewSwitchConnections(),
        flowVisor::acceptConnectionFromSwitch);
    reactor.loop();
  }
}
