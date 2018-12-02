package com.github.sherter.jcon.composer;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.*;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.common.base.MoreObjects;

public class Node {
  private String address;
  private Integer layer;

  public Node() {}

  public Node(String address, Integer layer) {
    this.address = address;
    this.layer = layer;
  }

  @JsonInclude(NON_NULL)
  public String getAddress() {
    return this.address;
  }

  public void setAddress(String listenAddress) {
    this.address = listenAddress;
  }

  @JsonInclude(NON_NULL)
  public Integer getLayer() {
    return layer;
  }

  public void setLayer(Integer layer) {
    this.layer = layer;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Node)) return false;

    Node node = (Node) o;

    if (address != null ? !address.equals(node.address) : node.address != null) return false;
    return layer != null ? layer.equals(node.layer) : node.layer == null;
  }

  @Override
  public int hashCode() {
    int result = address != null ? address.hashCode() : 0;
    result = 31 * result + (layer != null ? layer.hashCode() : 0);
    return result;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("address", address)
        .add("layer", layer)
        .omitNullValues()
        .toString();
  }
}
