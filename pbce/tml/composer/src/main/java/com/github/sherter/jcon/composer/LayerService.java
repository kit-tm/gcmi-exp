package com.github.sherter.jcon.composer;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

public class LayerService {

  private final AtomicInteger counter = new AtomicInteger();
  private final BiMap<Integer, Layer> layers = Maps.synchronizedBiMap(HashBiMap.create());

  @Nullable
  public Layer get(int id) {
    return layers.get(id);
  }

  public ImmutableMap<Integer, Layer> all() {
    return ImmutableMap.copyOf(layers);
  }

  public int createLogAndForwardLayer() throws IOException {
    int id = counter.incrementAndGet();
    LogAndForwardLayer layer = LogAndForwardLayer.create(this);
    layers.put(id, layer);
    return id;
  }

  public int createPaylessLayer() throws IOException {
    int id = counter.incrementAndGet();
    PaylessLayer layer = PaylessLayer.create(this);
    layers.put(id, layer);
    return id;
  }

  public int createPbceLayer() throws IOException {
    int id = counter.incrementAndGet();
    PbceLayer layer = PbceLayer.create(this);
    layers.put(id, layer);
    return id;
  }

  public int createPbce2Layer(LayerConfig config) throws IOException {
    int id = counter.incrementAndGet();
    Pbce2Layer layer = new Pbce2Layer(this, config.getConfig());
    layers.put(id, layer);
    return id;
  }

  public int createTableVisorLayer(LayerConfig config) throws IOException {
    int id = counter.incrementAndGet();
    TableVisorLayer layer = new TableVisorLayer(this, config.getConfig());
    layers.put(id, layer);
    return id;
  }

  void remove(Layer layer) {
    layers.inverse().remove(layer);
  }
}
