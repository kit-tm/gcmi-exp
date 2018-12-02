package com.github.sherter.jcon;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.function.Consumer;
import org.projectfloodlight.openflow.exceptions.OFParseError;
import org.projectfloodlight.openflow.protocol.OFFactories;
import org.projectfloodlight.openflow.protocol.OFMessage;

/**
 * Instances of this class are intended to be passed to the constructor of {@link
 * com.github.sherter.jcon.BufferingConsumer}
 */
public class OFMessageParsingConsumer implements Consumer<byte[]> {

  private final Consumer<? super OFMessage> messageConsumer;

  public OFMessageParsingConsumer(Consumer<? super OFMessage> ofMessageConsumer) {
    this.messageConsumer = ofMessageConsumer;
  }

  @Override
  public void accept(byte[] data) {
    ByteBuf buffer = null;
    try {
      buffer = Unpooled.wrappedBuffer(data);
      OFMessage ofMessage = OFFactories.getGenericReader().readFrom(buffer);
      messageConsumer.accept(ofMessage);
    } catch (OFParseError e) {
      throw new RuntimeException(e);
    } finally {
      if (buffer != null) {
        buffer.release();
      }
    }
  }
}
