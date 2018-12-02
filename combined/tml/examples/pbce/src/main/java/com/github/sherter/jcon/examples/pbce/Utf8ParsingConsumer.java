package com.github.sherter.jcon.examples.pbce;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public class Utf8ParsingConsumer implements Consumer<ByteBuffer> {

  private final Consumer<? super String> stringConsumer;

  Utf8ParsingConsumer(Consumer<? super String> stringConsumer) {
    this.stringConsumer = stringConsumer;
  }

  @Override
  public void accept(ByteBuffer data) {
    String decoded = StandardCharsets.UTF_8.decode(data).toString();
    stringConsumer.accept(decoded);
  }
}
