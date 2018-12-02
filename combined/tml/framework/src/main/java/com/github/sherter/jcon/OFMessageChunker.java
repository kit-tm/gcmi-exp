package com.github.sherter.jcon;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class OFMessageChunker implements Chunker {

  public static final Chunker INSTANCE = new OFMessageChunker();

  @Override
  public Iterable<byte[]> extract(ByteBuffer buffer) {
    buffer.order(ByteOrder.BIG_ENDIAN); // OpenFlow follows "most significant byte first"
    List<byte[]> messages = new ArrayList<>();
    int limit = buffer.limit();
    int currentPosition = buffer.position();
    while (limit - currentPosition >= 4) { // OpenFlow message length is at bytes 3 and 4
      int length = Short.toUnsignedInt(buffer.getShort(currentPosition + 2));
      if (length < 8) {
        // invalid message, put remaining bytes in "damaged" message at the end of the list
        length = buffer.remaining();
      }
      if (currentPosition + length > limit) {
        break; // we didn't receive the whole message yet
      }
      byte[] message = new byte[length];
      buffer.get(message);
      messages.add(message);
      currentPosition = buffer.position();
    }
    return messages;
  }
}
