package com.github.sherter.jcon.examples.flowvisor;

import com.github.sherter.jcon.OFMessageSerializingConsumer;
import com.github.sherter.jcon.networking.Handler;
import java.util.function.Consumer;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class GuestConnectionContext {

  private static final Logger log = LoggerFactory.getLogger(GuestConnectionContext.class);

  final Handler handler;
  final Consumer<OFMessage> sender;
  final SwitchConnectionContext switchConnectionContext;
  final Slice slice;

  GuestConnectionContext(
      Handler controllerHandler, SwitchConnectionContext switchConnectionContext, Slice slice) {
    this.handler = controllerHandler;
    OFMessageSerializingConsumer serializingConsumer =
        new OFMessageSerializingConsumer(bytes -> controllerHandler.send(bytes));
    this.sender =
        m -> {
          log.info("sending to guest ({}): {}", handler.remoteAddress(), m);
          serializingConsumer.accept(m);
        };
    this.switchConnectionContext = switchConnectionContext;
    this.slice = slice;
  }
}
