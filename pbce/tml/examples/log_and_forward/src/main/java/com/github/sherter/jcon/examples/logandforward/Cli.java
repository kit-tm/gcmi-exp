package com.github.sherter.jcon.examples.logandforward;

import static com.google.common.base.Preconditions.checkArgument;

import com.beust.jcommander.IStringConverter;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.github.sherter.jcon.networking.Reactor;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.Selector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class Cli {

  private static final Logger log = LoggerFactory.getLogger(Cli.class);

  public static void main(String[] args) throws IOException {
    Args parsedArgs = new Args();
    JCommander.newBuilder().addObject(parsedArgs).build().parse(args);

    Reactor reactor = new Reactor(Selector.open());
    LogAndForward logAndForward = new LogAndForward(parsedArgs.upstreamAddress, reactor);

    log.info(
        "Listening on {} and forwarding to {} ...",
        parsedArgs.listenAddress,
        parsedArgs.upstreamAddress);
    reactor.listen(
        parsedArgs.listenAddress,
        logAndForward.callbackFactoryForNewSwitchConnections(),
        logAndForward::acceptConnectionFromSwitch);
    reactor.loop();
  }

  static class Args {
    @Parameter(
      names = {"-l", "--listen"},
      converter = InetSocketAddressConverter.class,
      required = true,
      description = "format: 'host:port'; opens TCP server socket expecting OpenFlow packets"
    )
    InetSocketAddress listenAddress;

    @Parameter(
      names = {"-u", "--upstream"},
      converter = InetSocketAddressConverter.class,
      description = "format: 'host:port'; connection parameters for upper layer"
    )
    InetSocketAddress upstreamAddress;
  }

  static class InetSocketAddressConverter implements IStringConverter<InetSocketAddress> {
    @Override
    public InetSocketAddress convert(String value) {
      String[] splits = value.split(":");
      checkArgument(splits.length == 2);
      return new InetSocketAddress(splits[0], Integer.parseInt(splits[1]));
    }
  }
}
