package com.github.sherter.jcon.examples.pbce;

import static com.github.sherter.jcon.examples.pbce.Pbce.*;

import com.github.sherter.jcon.InjectingConsumer;
import com.github.sherter.jcon.networking.Handler;
import com.github.sherter.jcon.networking.Reactor;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.projectfloodlight.openflow.types.OFPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TelnetServer {

  private static final Logger log = LoggerFactory.getLogger(TelnetServer.class);

  private final Pbce pbce;

  public TelnetServer(Pbce pbce) {
    this.pbce = pbce;
  }

  public InetSocketAddress listenOn(Reactor reactor, @Nullable InetSocketAddress listenAddress)
      throws IOException {
    ServerSocketChannel channel =
        reactor.listen(
            listenAddress,
            callbackFactoryForNewClientConnections(),
            handler ->
                clientConnected(s -> handler.send(s.getBytes(StandardCharsets.UTF_8)), handler));
    return (InetSocketAddress) channel.socket().getLocalSocketAddress();
  }

  Function<Handler, Handler.Callbacks> callbackFactoryForNewClientConnections() {
    return clientHandler ->
        new Handler.Callbacks(
            new Utf8ParsingConsumer(
                new InjectingConsumer<>(
                    (String s, Consumer<String> c) -> commandReceived(s.trim(), c),
                    s -> clientHandler.send((s + "\n").getBytes(StandardCharsets.UTF_8)))),
            throwable -> clientDisconnected(throwable, clientHandler));
  }

  private void clientDisconnected(Throwable throwable, Handler handler) {
    log.info("Telnet session with {} ended", handler.remoteAddress());
  }

  void clientConnected(Consumer<String> sender, Handler handler) {
    log.info("Telnet client connected from {}", handler.remoteAddress());
    sender.accept(
        "\n\nHello there!\n"
            + "\n"
            + "This server supports these commands:\n"
            + "\n"
            + "'list': list all known OpenFlow datapaths\n"
            + "'evict': install backflow rules and enable delegation\n"
            + "'clear': remove eviction rules\n\n");
  }

  void commandReceived(String command, Consumer<String> lineSender) {
    List<Context> contexts = pbce.contexts();

    Context delegationSwitch;
    Context extensionSwitch;
    if (contexts.get(0).datapathId.getLong() == 1L) {
      delegationSwitch = contexts.get(0);
      extensionSwitch = contexts.get(1);
    } else {
      delegationSwitch = contexts.get(1);
      extensionSwitch = contexts.get(0);
    }
    switch (command) {
      case "list":
        pbce.contexts()
            .stream()
            .filter(c -> c.datapathId != null)
            .map(c -> c.datapathId.toString() + "; ports: " + c.switchPorts.toString())
            .forEach(lineSender);
        break;
      case "evict":
        lineSender.accept("Delegation Switch: " + delegationSwitch.datapathId);
        lineSender.accept("Extension Switch: " + extensionSwitch.datapathId);

        delegationSwitch.evictor.evict(OFPort.of(2), extensionSwitch, OFPort.of(1));
        break;
      case "clear":
        delegationSwitch.evictor.revert(extensionSwitch);
        break;
      default:
        lineSender.accept("unknown command '" + command + "'");
    }
  }
}
