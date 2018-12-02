package com.github.sherter.jcon.composer;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

@Path("layers")
@Singleton
public class LayersResource {

  @Inject
  LayerService layerService;

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public List<LayerWithId> getLayers() throws IOException {
    return layerService.all().entrySet().stream().map(e -> new LayerWithId(e.getKey(), e.getValue()))
        .collect(Collectors.toList());
  }

  @Path("/{id}")
  public LayerResource getLayer(@PathParam("id") String id) {
    int idNum = Integer.parseInt(id);
    return new LayerResource(layerService.get(idNum), idNum);
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  public Response post(@Context UriInfo info, LayerConfig config) throws IOException {
    int id;
    switch (config.getType()) {
    case "log_and_forward":
      id = layerService.createLogAndForwardLayer();
      break;
    case "payless":
      id = layerService.createPaylessLayer();
      break;
    case "pbce":
      id = layerService.createPbceLayer();
      break;
    case "pbce2":
      try {
        id = layerService.createPbce2Layer(config);
      } catch (Exception e) {
        e.printStackTrace();
        throw e;
      }
      break;
    case "tablevisor":
      try {
        id = layerService.createTableVisorLayer(config);
      } catch (Exception e) {
        e.printStackTrace();
        throw e;
      }
      break;
    default:
      return Response.status(Response.Status.BAD_REQUEST).build();
    }
    return Response.created(info.getRequestUriBuilder().path(Integer.toString(id)).build()).build();
  }

  public class LayerResource {

    private final Layer layer;
    private final int id;

    LayerResource(Layer layer, int id) {
      this.layer = layer;
      this.id = id;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response get() {
      return Response.ok().entity(new LayerWithId(id, layer)).build();
    }

    @DELETE
    public Response delete() {
      layer.destroy();
      return Response.ok().build();
    }
  }
}
