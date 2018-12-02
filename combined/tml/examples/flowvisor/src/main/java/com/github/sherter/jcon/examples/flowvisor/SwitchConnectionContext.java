package com.github.sherter.jcon.examples.flowvisor;

import com.github.sherter.jcon.OFMessageSerializingConsumer;
import com.github.sherter.jcon.networking.BackPressureable;
import com.github.sherter.jcon.networking.Handler;
import java.util.*;
import java.util.function.Consumer;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.types.DatapathId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class SwitchConnectionContext {

  private static final Logger log = LoggerFactory.getLogger(SwitchConnectionContext.class);
  final Handler handler;
  final Consumer<OFMessage> sender;
  DatapathId datapathId;
  private final Map<Slice, GuestConnectionContext> guestContexts = new HashMap<>();

  SwitchConnectionContext(Handler switchHandler) {
    this.handler = switchHandler;
    OFMessageSerializingConsumer serializingConsumer =
        new OFMessageSerializingConsumer(bytes -> switchHandler.send(bytes, null));
    this.sender =
        m -> {
          log.info("sending to switch ({}): {}", handler.remoteAddress(), m);
          serializingConsumer.accept(m);
        };
  }

  void addUpstream(GuestConnectionContext upstream) {
    guestContexts.put(upstream.slice, upstream);
  }

  GuestConnectionContext upstreamContextOf(Slice slice) {
    return guestContexts.get(slice);
  }

  Collection<GuestConnectionContext> upstreamContexts() {
    return Collections.unmodifiableCollection(guestContexts.values());
  }
}
