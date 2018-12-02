package com.github.sherter.jcon.networking;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.*;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nullable;

public class Reactor {

  private final Selector selector;

  public Reactor(Selector selector) {
    this.selector = selector;
  }

  public Reactor create() {
    Selector selector = null;
    try {
      selector = Selector.open();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return new Reactor(selector);
  }

  public void loop() {
    while (!Thread.interrupted()) {
      try {
        selector.select();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      Set<SelectionKey> selected = selector.selectedKeys();
      for (SelectionKey key : selected) {
        if (key.isValid()) {
          if (key.interestOps() == SelectionKey.OP_ACCEPT) {
            ((Acceptor) key.attachment()).handleEvent();
          } else {
            ((Handler) key.attachment()).handleEvent();
          }
        }
      }
      selected.clear();
    }
  }

  public void close() {
    for (SelectionKey key : selector.keys()) {
      if (key.interestOps() == SelectionKey.OP_ACCEPT) {
        ((Acceptor) key.attachment()).close();
      } else {
        ((Handler) key.attachment()).close();
      }
    }
    try {
      selector.close();
    } catch (IOException e) {
      throw new RuntimeException("closing reactor failed", e);
    }
  }

  // maybe add support for unix domain sockets later (using https://github.com/jnr/jnr-unixsocket)
  // public <T> T establish(
  //     UnixSocketAddress address,
  //     BiConsumer<T, byte[]> received,
  //     BiConsumer<T, Throwable> disconnected,
  //     Function<Handler<T>, T> callbackArgCalculator) {}
  // public <T> SocketAddress listen(
  //     UnixSocketAddress address,
  //     Consumer<? super T> connected,
  //     Supplier<? extends BiConsumer<? super T, byte[]>> receivedSupplier,
  //     Supplier<? extends BiConsumer<? super T, ? super Throwable>> disconnectedSupplier,
  //     Function<Handler<T>, T> callbackArgumentCalculator) {}

  /**
   * Establish a new connection and register it with this reactor.
   *
   * <p>Only to be called from the thread that runs the selection loop, otherwise will result in a
   * deadlock.
   *
   * @param remoteAddress the address to which to connect to
   * @param callbacksFactory
   * @return the context object as constructed by {@code contextFactory}
   */
  public Handler establish(
      SocketAddress remoteAddress, Function<? super Handler, Handler.Callbacks> callbacksFactory)
      throws IOException {
    SocketChannel channel = selector.provider().openSocketChannel();
    channel.configureBlocking(false);
    boolean connected = channel.connect(remoteAddress);
    int interestSet;
    if (!connected) {
      interestSet = SelectionKey.OP_CONNECT;
    } else {
      interestSet = SelectionKey.OP_READ;
    }
    SelectionKey key = channel.register(selector, interestSet);
    Handler handler = new Handler(channel, key, callbacksFactory);
    key.attach(handler);
    return handler;
  }

  /**
   * Serve on the given {@code localAddress}.
   *
   * <p>Only to be called from the thread that runs the selection loop, otherwise will result in a
   * deadlock.
   *
   * @param localAddress the address on which to listen for client connections. If the address is
   *     {@code null}, then the system will pick up an ephemeral port and a valid local address to
   *     bind the socket.
   * @param handlerCallbackFactory When a client connects to {@code localAddress}, a new {@link
   *     Handler} object is created for managing the connection. Since we operate in a non-blocking
   *     event loop, the new {@code Handler} needs to know what methods to call when data arrives on
   *     the socket or the connection is closed. This factory is responsible for producing those
   *     callback objects. The new Handler itself is passed as an argument to the factory, so the
   *     callback objects can reference the Handler.
   * @param connectCallback called when a new client has connected. The {@link Handler} responsible
   */
  public ServerSocketChannel listen(
      @Nullable InetSocketAddress localAddress,
      Function<? super Handler, Handler.Callbacks> handlerCallbackFactory,
      Consumer<? super Handler> connectCallback)
      throws IOException {
    ServerSocketChannel channel = selector.provider().openServerSocketChannel();
    channel.configureBlocking(false);
    channel.socket().bind(localAddress);
    Acceptor acceptor = new Acceptor(channel, connectCallback, handlerCallbackFactory, selector);
    channel.register(selector, SelectionKey.OP_ACCEPT, acceptor);
    return channel;
  }
}
