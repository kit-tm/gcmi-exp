package com.github.sherter.jcon.examples.pbce;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public class Utf8ParsingConsumer implements Consumer<byte[]> {

  private final Consumer<? super String> stringConsumer;

  Utf8ParsingConsumer(Consumer<? super String> stringConsumer) {
    this.stringConsumer = stringConsumer;
  }

  @Override
  public void accept(byte[] data) {
    String decoded = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(data)).toString();
    stringConsumer.accept(decoded);
  }
}
