package com.github.sherter.jcon.composer;

import com.google.common.collect.ImmutableList;
import com.google.common.graph.GraphBuilder;
import com.google.common.graph.MutableGraph;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import javax.inject.Inject;

class Composer {

  private final RoutingProxy proxy;
  private final InetSocketAddress controllerAddress;
  private final LayerService layerService;
  private Layer lowest;
  private Layer highest;

  private MutableGraph<Layer> graph = GraphBuilder.directed().allowsSelfLoops(false).build();

  @Inject
  Composer(RoutingProxy proxy, InetSocketAddress controllerAddress, LayerService layerService) {
    this.proxy = proxy;
    this.controllerAddress = controllerAddress;
    this.layerService = layerService;
  }

  void connectableForSwitches(Layer layer) {
    proxy.connectTo(
        new InetSocketAddress(InetAddress.getLoopbackAddress(), layer.listenAddress().getPort()));
    lowest = layer;
  }

  void connectToController(Layer layer) {
    layer.connectTo(ImmutableList.of(controllerAddress));
    highest = layer;
  }

  void connect(int lowerLayerId, int upperLayerId) {
    Layer lower = layerService.get(lowerLayerId);
    Layer upper = layerService.get(upperLayerId);
    lower.connectTo(
        ImmutableList.of(
            new InetSocketAddress(
                InetAddress.getLoopbackAddress(), upper.listenAddress().getPort())));
    graph.putEdge(lower, upper);
  }

  void remove(Layer layer) {
    graph.removeNode(layer);
  }
}
