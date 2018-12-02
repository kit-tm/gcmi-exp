package com.github.sherter.jcon.composer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.google.common.graph.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("edges")
@Singleton
public class EdgesResource {

  private static final Logger log = LoggerFactory.getLogger(EdgesResource.class);

  @Inject LayerService layers;
  private final MutableGraph<Node> graph = GraphBuilder.directed().allowsSelfLoops(false).build();
  private ConcurrentHashMap<InetSocketAddress, Layer> routingLayers = new ConcurrentHashMap<>();

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Set<EndpointPair<Node>> getIt() {
    return graph.edges();
  }

  /**
   * Allows to change the upstream controllers a layer connects to. All existing edges from the
   * source node are replaced by the new upstream edges. The source node may be an address,
   * indicating that an external application is trying to connect to this address, i.e. we must
   * install a server that listens on that address. Similarly, the target may be an address instead
   * of a layer id, which indicates that source node should connect to these addresses (i.e.
   * externally running controllers).
   *
   * <p>The JSON content looks like this:
   *
   * <pre>
   *    { "source": <Node>,
   *      "targets": [<Node>] }
   * </pre>
   *
   * A node can either be an address (see above) or the id of a previously created layer.
   *
   * <p>Full request might look like this:
   *
   * <pre>
   *     { "source": { "layer" : 5},
   *      "targets": [ {"layer" : 6} ] }
   * </pre>
   *
   * This will result in layer 5 connecting to layer 6 from now on.
   */
  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response post(EdgePosting edgePosting) throws IOException {
    JsonNode error = check(edgePosting);
    if (error != null) {
      return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
    }
    Layer sourceLayer;
    if (edgePosting.getSource().getLayer() != null) {
      sourceLayer = layers.get(edgePosting.getSource().getLayer());
    } else {
      String addressString = edgePosting.getSource().getAddress();
      InetSocketAddress address =
          new InetSocketAddress(
              addressString.split(":")[0], Integer.parseInt(addressString.split(":")[1]));
      sourceLayer = routingLayers.get(address);
      if (sourceLayer == null) {
        sourceLayer = RoutingLayer.create(address);
        routingLayers.put(address, sourceLayer);
      }
    }
    List<InetSocketAddress> addresses = getTargetAddresses(edgePosting.getTargets());
    sourceLayer.connectTo(addresses);
    graph.removeNode(edgePosting.getSource());
    for (Node target : edgePosting.getTargets()) {
      graph.putEdge(edgePosting.getSource(), target);
    }
    return Response.ok().entity(edgePosting).build();
  }

  private List<InetSocketAddress> getTargetAddresses(List<Node> targets) {
    List<InetSocketAddress> list = new ArrayList<>(targets.size());
    for (Node n : targets) {
      if (n.getAddress() != null) {
        list.add(parse(n.getAddress()));
      } else {
        Layer layer = layers.get(n.getLayer());
        list.add(layer.listenAddress());
      }
    }
    return list;
  }

  static InetSocketAddress parse(String addressPort) {
    String[] parts = addressPort.split(":");
    return new InetSocketAddress(parts[0], Integer.parseInt(parts[1]));
  }

  JsonNode check(EdgePosting posting) {
    List<Node> targets = posting.getTargets();
    if (targets == null || targets.size() == 0) {
      return JsonNodeFactory.instance.objectNode().put("error", "no targets found");
    }
    Node source = posting.getSource();
    if (source == null) {
      return JsonNodeFactory.instance.objectNode().put("error", "no source found");
    }
    if ((source.getLayer() == null && source.getAddress() == null)
        || (source.getLayer() != null && source.getAddress() != null)) {
      return JsonNodeFactory.instance
          .objectNode()
          .put("error", "need (exactly) one of 'layer' or 'address' field in source");
    }
    for (Node target : targets) {
      if ((target.getLayer() == null && target.getAddress() == null)
          || (target.getLayer() != null && target.getAddress() != null)) {
        return JsonNodeFactory.instance
            .objectNode()
            .put("error", "need (exactly) one of 'layer' or 'address' field in all targets");
      }
    }
    Integer layerId = posting.getSource().getLayer();
    if (layerId != null) {
      Layer sourceLayer = layers.get(layerId);
      if (sourceLayer == null) {
        return JsonNodeFactory.instance
            .objectNode()
            .put("error", "no layer with id '" + layerId + "' found.");
      }
    }
    for (Node target : targets) {
      layerId = target.getLayer();
      if (layerId != null) {
        Layer layer = layers.get(layerId);
        if (layer == null) {
          return JsonNodeFactory.instance
              .objectNode()
              .put("error", "no layer with id '" + layerId + "' found.");
        }
      }
    }
    return null;
  }
}
