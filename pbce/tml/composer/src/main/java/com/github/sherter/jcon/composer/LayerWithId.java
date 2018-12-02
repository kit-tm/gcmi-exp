package com.github.sherter.jcon.composer;

public class LayerWithId {
  private final int id;
  final Layer layer;

  LayerWithId(int id, Layer layer) {
    this.id = id;
    this.layer = layer;
  }

  public int getId() {
    return id;
  }

  public String getType() {
    return layer.type();
  }

  public Object getConfig() {
    return layer.configuration();
  }
}
