package com.github.sherter.jcon;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.projectfloodlight.openflow.protocol.OFMessage;

/** Handles XIDs for a upstream/downstream connection pair. */
public class XidManager {

  private final AtomicInteger internalGenerator = new AtomicInteger(new Random().nextInt());
  private final AtomicInteger downstreamGenerator = new AtomicInteger(new Random().nextInt());
  private final AtomicInteger upstreamGenerator = new AtomicInteger(new Random().nextInt());

  final BiMap<Long, Long> upperToInternal = HashBiMap.create();
  final BiMap<Long, Long> lowerToInternal = HashBiMap.create();
  final Set<Long> originInternalIds = new HashSet<>();

  OFMessage toInternalFromUpstream(OFMessage message) {
    Long internal = upperToInternal.get(message.getXid());
    if (internal == null) {
      internal = Integer.toUnsignedLong(internalGenerator.incrementAndGet());
      upperToInternal.put(message.getXid(), internal);
    }
    return message.createBuilder().setXid(internal).build();
  }

  /**
   * @param message
   * @return
   */
  OFMessage internalToDownstream(OFMessage message) {
    Long downstreamId = lowerToInternal.inverse().get(message.getXid());
    if (downstreamId == null) {
      downstreamId = Integer.toUnsignedLong(downstreamGenerator.incrementAndGet());
      lowerToInternal.inverse().put(message.getXid(), downstreamId);
    }
    return message.createBuilder().setXid(downstreamId).build();
  }

  OFMessage toInternalFromDownstream(OFMessage message) {
    Long internal = lowerToInternal.get(message.getXid());
    if (internal == null) {
      internal = Integer.toUnsignedLong(internalGenerator.incrementAndGet());
      lowerToInternal.put(message.getXid(), internal);
    }
    return message.createBuilder().setXid(internal).build();
  }

  OFMessage internalToUpstream(OFMessage message) {
    Long upstreamId = upperToInternal.inverse().get(message.getXid());
    if (upstreamId != null) {
      return message.createBuilder().setXid(upstreamId).build();
    }
    upstreamId = Integer.toUnsignedLong(upstreamGenerator.incrementAndGet());
    upperToInternal.inverse().put(message.getXid(), upstreamId);
    return message.createBuilder().setXid(upstreamId).build();
  }

  public long newOriginXid() {
    long xid = Integer.toUnsignedLong(internalGenerator.incrementAndGet());
    originInternalIds.add(xid);
    return xid;
  }

  boolean isOriginXid(long xid) {
    return originInternalIds.contains(xid);
  }
}
