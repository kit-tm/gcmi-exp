package com.github.sherter.jcon.networking;

import static com.google.common.base.Preconditions.checkState;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
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
public class Handler {

  private static final Logger log = LoggerFactory.getLogger(Handler.class);

  private static final int RECEIVE_BUFFER_SIZE = 4096;

  private final ByteBuffer receiveBuffer = ByteBuffer.allocate(RECEIVE_BUFFER_SIZE);
  private final Deque<ByteBuffer> sendQueue = new ConcurrentLinkedDeque<>();

  // guards close() to make sure the disconnect handler is only called once
  private final Object closeChannelLock = new Object();
  private final SocketChannel socketChannel;
  private final SelectionKey selectionKey;

  private final Consumer<? super byte[]> receivedCallback;
  private final Consumer<? super Throwable> disconnectedCallback;

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
    assert selectionKey.channel() == socketChannel;
    this.socketChannel = socketChannel;
    this.selectionKey = selectionKey;
    Callbacks callbacks = callbacksFactory.apply(this);
    // we use a pair so we can share state that depends on "handler" between the two consumers.
    this.receivedCallback = callbacks.received;
    this.disconnectedCallback = callbacks.disconnected;
  }

  public static final class Callbacks {
    private final Consumer<? super byte[]> received;
    private final Consumer<? super Throwable> disconnected;

    public Callbacks(Consumer<? super byte[]> received, Consumer<? super Throwable> disconnected) {
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
      // If 'send' wasn't called before, 'write' will switch to READ mode
      // without actually writing anything to the socket.
      selectionKey.interestOps(SelectionKey.OP_WRITE | SelectionKey.OP_READ);
    }
  }

  private void read() throws IOException {
    log.trace("reading, local: {}, remote: {}, handler: {}", localAddress(), remoteAddress(), this);
    int read = socketChannel.read(receiveBuffer);
    if (read == -1) {
      // end-of-stream (disconnect)
      selectionKey.cancel();
      socketChannel.close();
      disconnectedCallback.accept(null);
    } else {
      receiveBuffer.flip();
      byte[] data = new byte[receiveBuffer.limit()];
      receiveBuffer.get(data);
      receiveBuffer.compact();
      receivedCallback.accept(data);
    }
  }

  private void write() throws IOException {
    log.trace("writing, local: {}, remote: {}, handler: {}", localAddress(), remoteAddress(), this);
    ByteBuffer b;
    while ((b = sendQueue.poll()) != null) {
      socketChannel.write(b);
      if (b.hasRemaining()) {
        sendQueue.addFirst(b);
        break;
      }
    }
    synchronized (sendQueue) {
      if (sendQueue.isEmpty()) {
        selectionKey.interestOps(SelectionKey.OP_READ);
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
  public void send(byte[] data) throws IllegalStateException {
    checkState(socketChannel.isOpen(), "Connection is closed.");
    synchronized (sendQueue) {
      sendQueue.addLast(ByteBuffer.wrap(data));
    }
    if (socketChannel.isConnected()) { // if not, finishConnect will switch to write mode
      selectionKey.interestOps(SelectionKey.OP_WRITE | SelectionKey.OP_READ);
      selectionKey.selector().wakeup();
    }
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
