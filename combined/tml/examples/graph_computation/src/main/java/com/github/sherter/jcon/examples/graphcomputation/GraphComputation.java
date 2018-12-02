package com.github.sherter.jcon.examples.graphcomputation;

import com.github.sherter.jcon.InterceptableForwarder;
import com.github.sherter.jcon.networking.Reactor;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Multimap;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.Selector;
import java.util.*;
import java.util.concurrent.TimeUnit;

import org.jgrapht.Graph;
import org.jgrapht.GraphPath;
import org.jgrapht.UndirectedGraph;
import org.jgrapht.VertexFactory;
import org.jgrapht.alg.interfaces.ShortestPathAlgorithm;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.generate.GnmRandomGraphGenerator;
import org.jgrapht.generate.ScaleFreeGraphGenerator;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleGraph;
import org.jgrapht.graph.SimpleWeightedGraph;
import org.jgrapht.traverse.RandomWalkIterator;
import org.projectfloodlight.openflow.protocol.*;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GraphComputation {

  private final Graph<Object, DefaultEdge> graph;
  private final ArrayList<Object> vertices;
  private final Random random;

  public GraphComputation(int arity, int height) {
    graph = generateTree(arity, height);
    vertices = new ArrayList<>(graph.vertexSet());
    System.out.println(vertices.size());
    random = new Random();
  }

  void receiveFromController(OFPacketOut packetOut, InterceptableForwarder.Context context) {
    DijkstraShortestPath<Object, DefaultEdge> dijkstra = new DijkstraShortestPath<>(graph);
    Object source = vertices.get(random.nextInt(vertices.size()));
    Object target = vertices.get(random.nextInt(vertices.size()));
    List<Object> shortestPath = dijkstra.getPath(source, target).getVertexList();
    System.out.println(shortestPath);
    System.out.println(shortestPath.size());
    context.sendDownstream(packetOut);
  }

  public static void main(String[] args) throws IOException, InterruptedException {
    GraphComputation copm = new GraphComputation(3, 10);
    copm.receiveFromController(
        OFFactories.getFactory(OFVersion.OF_10).buildPacketOut().build(), null);

    //    GraphComputation payless = new GraphComputation(1, TimeUnit.SECONDS);
    //    Reactor reactor = new Reactor(Selector.open());
    //    InterceptableForwarder interceptor =
    //        new InterceptableForwarder.Builder(
    //                reactor, new InetSocketAddress(InetAddress.getLoopbackAddress(), 16653))
    //            .interceptDownstream(OFStatsRequest.class, payless::receiveFromController)
    //            .interceptUpstream(OFStatsReply.class, payless::receiveFromSwitch)
    //            .build();
    //    interceptor.listenOn(new InetSocketAddress(InetAddress.getLoopbackAddress(), 6653));
    //    reactor.loop();
  }

  public GraphComputation registerWith(InterceptableForwarder.Builder builder) {
    builder.interceptDownstream(OFPacketOut.class, this::receiveFromController);
    return this;
  }

  static Graph<Object, DefaultEdge> generateTree(int arity, int height) {
    Graph<Object, DefaultEdge> g = new SimpleGraph<>(DefaultEdge.class);
    Object root = new Object();
    if (height > 0) {
      g.addVertex(root);
    }
    attachChildrenRecursively(g, root, arity, height - 1);
    return g;
  }

  static void attachChildrenRecursively(
      Graph<? super Object, ? super DefaultEdge> g, Object current, int arity, int height) {
    if (height > 0) {
      for (int i = 0; i < arity; i++) {
        Object child = new Object();
        g.addVertex(child);
        g.addEdge(current, child);
        attachChildrenRecursively(g, child, arity, height - 1);
      }
    }
  }
}
