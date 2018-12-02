package com.github.sherter.jcon.networking;

import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.Queues;
import com.google.common.util.concurrent.RateLimiter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@code Handler} is responsible for one TCP connection and takes part in one {@link
 * Selector#select() select} loop. {@link #send(byte[]) send} is a non-blocking operation that will
 * return immediately and receiving data from the TCP connection is accomplished by means of a
 * callback method.
 */
public class Handler implements BackPressureable {

  private static final Logger log = LoggerFactory.getLogger(Handler.class);

  private static final int RECEIVE_BUFFER_SIZE = 16384;
  private static final int INITIAL_SEND_BUFFER_SIZE = 2 * 65536; // 2 * max openflow message length
  private static final int BACKPRESSURE_INITIAL_ENABLE_UPPER_LIMIT = 1024;
  private static final int BACKPRESSURE_INITIAL_DISABLE_LOWER_LIMIT = 1024;
  private int backpressureEnableLimit = BACKPRESSURE_INITIAL_ENABLE_UPPER_LIMIT;
  private int backpressureDisableLimit = BACKPRESSURE_INITIAL_DISABLE_LOWER_LIMIT;


  private final ByteBuffer receiveBuffer = ByteBuffer.allocate(RECEIVE_BUFFER_SIZE);

  private final ByteBuf sendBuffer = Unpooled.buffer(INITIAL_SEND_BUFFER_SIZE);

  // guards close() to make sure the disconnect handler is only called once
  private final Object closeChannelLock = new Object();
  private final SocketChannel socketChannel;
  private final SelectionKey selectionKey;

  private final Consumer<? super ByteBuffer> receivedCallback;
  private final Consumer<? super Throwable> disconnectedCallback;
  private Set<BackPressureable> backPressureables = new HashSet<>();

  private static double INITAL_READ_RATE_LIMIT = 1000;
  private volatile RateLimiter rateLimiter = RateLimiter.create(INITAL_READ_RATE_LIMIT);

  /**
   * @param socketChannel the channel to read from and write to
   * @param selectionKey a token representing the registration of {@code socketChannel} with a
   *     {@link Selector}
   * @param receiveCallbackFactory called once in this constructor with {@code this} as an argument
   *     to create a callback method which is called when new data is received on the {@code
   *     socketChannel}.
   * @param disconnectCallbackFactory called once in this constructor with {@code this} as an
   *     argument to create a callback method which is called when the connection is broken. The
   *     created callback will be called at most once. In case the connection was interrupted
   *     unexpectedly, the cause is passed as argument to the callback. Otherwise the argument for
   *     the callback will be {@code null}.
   */
  Handler(
      SocketChannel socketChannel,
      SelectionKey selectionKey,
      Function<? super Handler, Callbacks> callbacksFactory) {
    try {
      log.trace(
          "Handler constructor called: {}\nlocal: {}\nremote: {}",
          this,
          socketChannel.getLocalAddress(),
          socketChannel.getRemoteAddress());
    } catch (IOException e) {
      e.printStackTrace();
    }
    assert selectionKey.channel() == socketChannel;
    this.socketChannel = socketChannel;
    this.selectionKey = selectionKey;
    Callbacks callbacks = callbacksFactory.apply(this);
    // we use a pair so we can share state that depends on "handler" between the two consumers.
    this.receivedCallback = callbacks.received;
    this.disconnectedCallback = callbacks.disconnected;
  }

  @Override
  public void pressure() {
//    synchronized (rateLimiter) {
//      rateLimiter.setRate(rateLimiter.getRate() / 2);
//    }
    selectionKey.interestOps(selectionKey.interestOps() & ~SelectionKey.OP_READ);
    selectionKey.selector().wakeup();
  }

  @Override
  public void unpressure() {
//    synchronized (rateLimiter) {
//      rateLimiter.setRate(rateLimiter.getRate() * 2);
//    }
    selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_READ);
    selectionKey.selector().wakeup();
  }

  public static final class Callbacks {
    private final Consumer<? super ByteBuffer> received;
    private final Consumer<? super Throwable> disconnected;

    public Callbacks(
        Consumer<? super ByteBuffer> received, Consumer<? super Throwable> disconnected) {
      this.received = received;
      this.disconnected = disconnected;
    }
  }

  void handleEvent() {
    try {
      if (selectionKey.isConnectable()) {
        finishConnect();
        return;
      }
      if (selectionKey.isWritable()) {
        write();
      }
      if (selectionKey.isReadable()) {
        read();
      }
    } catch (Throwable t) {
      handleBrokenConnection(t);
    }
  }

  private void finishConnect() throws IOException {
    boolean connected = socketChannel.finishConnect();
    if (connected) {
      // Switching to WRITE mode now because 'send' may have been called
      // before the connection was fully established.
      selectionKey.interestOps(SelectionKey.OP_WRITE | SelectionKey.OP_READ);
    }
  }

  private void read() throws IOException {
    //    log.trace("reading, local: {}, remote: {}, handler: {}", localAddress(), remoteAddress(), this);
//    if (!rateLimiter.tryAcquire()) {
//      return;
//    }
    int read = socketChannel.read(receiveBuffer);
    log.trace("{}: read: {} bytes", this, read);
    if (read == -1) {
      // end-of-stream (disconnect)
      selectionKey.cancel();
      socketChannel.close();
      disconnectedCallback.accept(null);
    } else {
      receiveBuffer.flip();
      receivedCallback.accept(receiveBuffer);
      receiveBuffer.compact();
    }
  }

  private void write() throws IOException {
    //    log.trace("writing, local: {}, remote: {}, handler: {}", localAddress(), remoteAddress(), this);
    synchronized (sendBuffer) {
      int written = socketChannel.write(sendBuffer.nioBuffer());
      sendBuffer.readerIndex(sendBuffer.readerIndex() + written);
      sendBuffer.discardSomeReadBytes();
      unpressure(); // When we write something, we may expect a response: unpressure so we can receive it
      if (sendBuffer.readableBytes() == 0) {
        selectionKey.interestOps(selectionKey.interestOps() & ~SelectionKey.OP_WRITE);
      }
      if (sendBuffer.readableBytes() < backpressureDisableLimit) {
        backPressureables.forEach(r -> r.unpressure());
      }
    }
  }

  private void handleBrokenConnection(Throwable t) {
    selectionKey.cancel();
    try {
      socketChannel.close();
    } catch (IOException e) {
      // pass on the original exception (see finally block)
    } finally {
      disconnectedCallback.accept(t);
    }
  }

  /**
   * Sends {@code data} through this connection.
   *
   * <p>This operation is non-blocking, i.e. the method returns immediately after {@code data} is
   * put into the send queue.
   *
   * @throws IllegalStateException if this connection is closed
   */
  public void send(ByteBuffer data, BackPressureable producer) throws IllegalStateException {
    checkState(socketChannel.isOpen(), "Connection is closed.");
    synchronized (sendBuffer) {
      if (sendBuffer.readableBytes() > backpressureEnableLimit && producer != null) {
        producer.pressure();
        backPressureables.add(producer);
      }
      sendBuffer.writeBytes(data);
      selectionKey.interestOps(SelectionKey.OP_WRITE | selectionKey.interestOps());
      selectionKey.selector().wakeup();
    }

    //    synchronized (queueLock) {
    //      ByteBuffer send = ByteBuffer.allocate(data.remaining());
    //      log.trace("{}: send was called ({} bytes)", this, data.remaining());
    //      send.put(data);
    //      send.flip();
    //      sendQueue.addLast(send);
    //      log.trace("{}: send queue size is {} (in 'send()')", this, sendQueue.size());
    //      if (socketChannel.isConnected()) { // if not, finishConnect will switch to write mode
    //        //      log.trace("Switching to OP_WRITE");
    //        log.trace("switching to OP_WRITE (in send)");
    //        selectionKey.interestOps(SelectionKey.OP_WRITE | SelectionKey.OP_READ);
    //        selectionKey.selector().wakeup();
    //      }
    //      return true;
    //    }
  }

  /**
   * Closes this connection.
   *
   * <p>If not already closed, closing this connection will trigger the {@link
   * Callbacks#disconnected} callback.
   */
  public void close() {
    synchronized (closeChannelLock) {
      if (socketChannel.isOpen()) {
        try {
          socketChannel.close();
          selectionKey.cancel();
          disconnectedCallback.accept(null);
        } catch (IOException e) {
          handleBrokenConnection(e);
        }
      }
    }
  }

  /** Returns the remote address of this connection */
  public @Nullable SocketAddress remoteAddress() {
    return socketChannel.socket().getRemoteSocketAddress();
  }

  /** Returns the remote address of this connection */
  public @Nullable SocketAddress localAddress() {
    return socketChannel.socket().getLocalSocketAddress();
  }
}
