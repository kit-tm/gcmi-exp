package com.github.sherter.jcon;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.ByteBuffer;
import java.util.function.Consumer;
import org.projectfloodlight.openflow.protocol.OFMessage;

public class OFMessageSerializingConsumer implements Consumer<OFMessage> {

  private final Consumer<? super ByteBuffer> rawBytesConsumer;

  public OFMessageSerializingConsumer(Consumer<? super ByteBuffer> rawBytesConsumer) {
    this.rawBytesConsumer = rawBytesConsumer;
  }

  @Override
  public void accept(OFMessage message) {
    ByteBuf b = Unpooled.buffer();
    message.writeTo(b);
    rawBytesConsumer.accept(b.nioBuffer());
    b.release();
  }
}
