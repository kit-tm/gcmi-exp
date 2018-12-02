package com.github.sherter.jcon;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import java.util.TreeMap;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.projectfloodlight.openflow.protocol.OFMessage;

/** Handles XIDs for a upstream/downstream connection pair. */
public class SingleXidManager {

  private final AtomicInteger internalGenerator = new AtomicInteger(new Random().nextInt());
  private final AtomicInteger externalGenerator = new AtomicInteger(new Random().nextInt());
  private final BiMap<Long, Long> mapping = HashBiMap.create();
  private final TreeMap<Long, Long> graceMapping = new TreeMap<Long, Long>();

  public void cleanup() {
    while (true) {
      Map.Entry<Long, Long> entry = graceMapping.firstEntry();
      if (entry == null || System.nanoTime() < entry.getKey() + 10000000000L) {
        return;
      }
      graceMapping.remove(entry.getKey());
      mapping.inverse().remove(entry.getValue());
    }
  }

  /**
   * @param message
   * @return
   */
  public OFMessage fromInternal(OFMessage message) {
    cleanup();
    Long downstreamId = mapping.inverse().remove(message.getXid());
    if (downstreamId == null) {
      downstreamId = Integer.toUnsignedLong(externalGenerator.incrementAndGet());
      mapping.inverse().put(message.getXid(), downstreamId);
      graceMapping.put(System.nanoTime(), message.getXid());
    }
    return message.createBuilder().setXid(downstreamId).build();
  }

  public OFMessage toInternal(OFMessage message) {
    cleanup();
    Long internal = mapping.remove(message.getXid());
    if (internal == null) {
      internal = Integer.toUnsignedLong(internalGenerator.incrementAndGet());
      mapping.put(message.getXid(), internal);
      graceMapping.put(System.nanoTime(), internal);
    }
    return message.createBuilder().setXid(internal).build();
  }
}
