package com.github.sherter.jcon.composer;

import java.util.List;

public class EdgePosting {
  private Node source;
  private List<Node> targets;

  public Node getSource() {
    return source;
  }

  public void setSource(Node source) {
    this.source = source;
  }

  public List<Node> getTargets() {
    return targets;
  }

  public void setTargets(List<Node> targets) {
    this.targets = targets;
  }
}
