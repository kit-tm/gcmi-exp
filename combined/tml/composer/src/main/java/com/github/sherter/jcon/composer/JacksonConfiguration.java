package com.github.sherter.jcon.composer;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.common.graph.EndpointPair;
import java.io.IOException;
import javax.ws.rs.ext.ContextResolver;
import javax.ws.rs.ext.Provider;

@Provider
public class JacksonConfiguration implements ContextResolver<ObjectMapper> {
  private ObjectMapper objectMapper;

  public JacksonConfiguration() throws Exception {
    this.objectMapper = new ObjectMapper();
    this.objectMapper
        .registerModule(
            new SimpleModule().addSerializer(EndpointPair.class, new EndpointPairSerializer()))
        .configure(DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES, false)
        .configure(SerializationFeature.INDENT_OUTPUT, true);
  }

  @Override
  public ObjectMapper getContext(Class<?> objectType) {
    return objectMapper;
  }

  /**
   * For serializing edges of composition graph.
   *
   * @see EdgesResource
   */
  @SuppressWarnings("rawtypes")
  static class EndpointPairSerializer extends JsonSerializer<EndpointPair> {
    @Override
    @SuppressWarnings("rawtypes")
    public void serialize(EndpointPair value, JsonGenerator gen, SerializerProvider serializers)
        throws IOException, JsonProcessingException {
      gen.writeStartObject();
      gen.writeObjectField("source", value.source());
      gen.writeObjectField("target", value.target());
      gen.writeEndObject();
    }
  }
}
