package com.github.sherter.jcon.networking;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class Acceptor {

  private static final Logger log = LoggerFactory.getLogger(Acceptor.class);

  private final ServerSocketChannel channel;
  private final Consumer<? super Handler> connectedCallback;
  private final Function<? super Handler, Handler.Callbacks> handlerCallbackFactory;
  private final Selector selector;

  Acceptor(
      ServerSocketChannel channel,
      Consumer<? super Handler> connectedCallback,
      Function<? super Handler, Handler.Callbacks> handlerCallbackFactory,
      Selector selector) {
    this.channel = channel;
    this.connectedCallback = connectedCallback;
    this.handlerCallbackFactory = handlerCallbackFactory;
    this.selector = selector;
  }

  // called by the reactor this acceptor is associated with when a pending
  // connection request is available
  void handleEvent() {
    try {
      SocketChannel socketChannel = channel.accept();
      assert socketChannel != null; // we've received an event so this shouldn't be null
      socketChannel.configureBlocking(false);
      SelectionKey key = socketChannel.register(selector, SelectionKey.OP_READ);
      Handler handler = new Handler(socketChannel, key, handlerCallbackFactory);
      key.attach(handler);
      connectedCallback.accept(handler);
    } catch (IOException e) {
      // TODO better handle this somewhere else
      log.error("failed to accept and configure a new client", e);
    }
  }

  void close() {
    if (channel.isOpen()) {
      try {
        channel.close();
      } catch (IOException e) {
        throw new RuntimeException("closing server socket channel failed", e);
      }
    }
  }
}
