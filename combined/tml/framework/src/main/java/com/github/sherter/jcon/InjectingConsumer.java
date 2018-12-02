package com.github.sherter.jcon;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class InjectingConsumer<T, U> implements Consumer<T> {

  private final BiConsumer<? super T, ? super U> target;
  private final U injected;

  public InjectingConsumer(BiConsumer<? super T, ? super U> target, U injected) {
    this.target = target;
    this.injected = injected;
  }

  @Override
  public void accept(T t) {
    target.accept(t, injected);
  }
}
