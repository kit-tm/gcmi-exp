package com.github.sherter.jcon;

import java.util.function.Consumer;
import org.projectfloodlight.openflow.protocol.OFMessage;

public class OFMessageSerializingConsumer implements Consumer<OFMessage> {

  private final Consumer<? super byte[]> rawBytesConsumer;

  public OFMessageSerializingConsumer(Consumer<? super byte[]> rawBytesConsumer) {
    this.rawBytesConsumer = rawBytesConsumer;
  }

  @Override
  public void accept(OFMessage message) {
    rawBytesConsumer.accept(Utils.serialize(message));
  }
}
