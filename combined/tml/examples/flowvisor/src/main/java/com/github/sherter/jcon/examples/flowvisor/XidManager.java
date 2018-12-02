package com.github.sherter.jcon.examples.flowvisor;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Manages the mapping between guest xids and FlowVisor xids. */
class XidManager {
  private final AtomicLong counter = new AtomicLong();

  private final Map<Long, XidContext> flowVisorXidContexts = new HashMap<>();

  XidContext remove(long flowVisorXid) {
    return flowVisorXidContexts.remove(flowVisorXid);
  }

  long create() {
    return counter.incrementAndGet();
  }

  XidContext create(long guestControllerXid, GuestConnectionContext guestContext) {
    XidContext context =
        new XidContext(counter.incrementAndGet(), guestControllerXid, guestContext);
    flowVisorXidContexts.put(context.flowVisorXid, context);
    return context;
  }
}
