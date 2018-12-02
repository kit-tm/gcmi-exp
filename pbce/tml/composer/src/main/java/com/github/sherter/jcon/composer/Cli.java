package com.github.sherter.jcon.composer;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import java.net.InetSocketAddress;

class Cli {

  @Parameter(
    names = {"listen-port"},
    description =
        "The port number to listen on (integer between 1 and 65535). If not specified, a random free port will be chosen. "
  )
  private Integer port = null;

  @Parameter(
    names = {"listen-address"},
    description = "The address to listen on (if not specified, listen on all interfaces)"
  )
  private String address = null;

  private Cli() {}

  static Cli parseArgs(String[] args) {
    Cli cli = new Cli();
    JCommander.newBuilder().addObject(cli).build().parse(args);
    InetSocketAddress inetSocketAddress =
        new InetSocketAddress(cli.address, cli.port == null ? 0 : cli.port);
    return cli;
  }
}
