package com.github.sherter.jcon;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

/**
 * The buffer grows as necessary (but doesn't shrink) to fit a whole message according to the {@link
 * Chunker}
 */
public class BufferingConsumer implements Consumer<ByteBuffer> {

  private final Consumer<? super byte[]> chunkConsumer;
  private final Chunker chunker;
  private ByteBuffer buffer;

  public BufferingConsumer(Consumer<? super byte[]> chunkConsumer, Chunker chunker) {
    this.chunkConsumer = chunkConsumer;
    this.chunker = chunker;
  }

  @Override
  public void accept(ByteBuffer data) {
    if (buffer == null) {
      buffer = ByteBuffer.allocate(allocationSizeHeuristic(0, 0, data.remaining()));
    }
    if (buffer.remaining() < data.remaining()) {
      ByteBuffer newBuffer =
          ByteBuffer.allocate(
              allocationSizeHeuristic(buffer.capacity(), buffer.remaining(), data.remaining()));
      buffer.flip();
      newBuffer.put(buffer);
      buffer = newBuffer;
    }
    buffer.put(data);
    buffer.flip();
    for (byte[] chunk : chunker.extract(buffer)) {
      chunkConsumer.accept(chunk);
    }
    buffer.compact();
  }

  private int allocationSizeHeuristic(int previousCapacity, int remaining, int newDataSize) {
    int newCapacity =
        previousCapacity + (newDataSize - remaining); // represents the minimal size heuristic
    assert newDataSize - remaining
        >= newCapacity - previousCapacity; // otherwise new data doesn't fit
    return newCapacity;
  }
}
