package com.github.sherter.jcon;

import java.nio.ByteBuffer;

public interface Chunker {

  /** Last element in the iterable may contain invalid message */
  Iterable<byte[]> extract(ByteBuffer buffer);
}
