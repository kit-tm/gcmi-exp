package com.github.sherter.jcon.examples.flowvisor;

import java.net.InetSocketAddress;

class Slice {

  final InetSocketAddress controllerAddress;

  Slice(InetSocketAddress controllerAddress) {
    this.controllerAddress = controllerAddress;
  }

  Slice(String ip, int port) {
    this.controllerAddress = new InetSocketAddress(ip, port);
  }
}
