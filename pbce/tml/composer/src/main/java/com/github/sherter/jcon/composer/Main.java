package com.github.sherter.jcon.composer;

import java.io.IOException;
import java.net.URI;
import javax.inject.Singleton;
import javax.ws.rs.core.UriBuilder;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

public class Main {

  public static void main(String[] args) throws IOException {

    URI baseUri = UriBuilder.fromUri("http://localhost/").port(8080).build();
    ResourceConfig config = new ResourceConfig(LayersResource.class, EdgesResource.class);
    config.register(
        new AbstractBinder() {
          @Override
          protected void configure() {
            bind(Composer.class).to(Composer.class).in(Singleton.class);
            bind(LayerService.class).to(LayerService.class).in(Singleton.class);
          }
        });
    config.packages("com.github.sherter.jcon.composer");

    HttpServer server = GrizzlyHttpServerFactory.createHttpServer(baseUri, config);
    server.start();
  }
}
