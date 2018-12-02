package com.github.sherter.jcon.composer;

import java.net.InetSocketAddress;
import java.util.List;

interface Layer {

  void destroy();

  InetSocketAddress listenAddress();

  void connectTo(List<InetSocketAddress> addresses);

  default Object configuration() {
    return null;
  }

  String type();
}
