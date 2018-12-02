package com.github.sherter.jcon.composer;

import com.fasterxml.jackson.databind.JsonNode;

public class LayerConfig {

  private String type;
  private JsonNode config;

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public JsonNode getConfig() {
    return config;
  }

  public void setConfig(JsonNode config) {
    this.config = config;
  }
}
