package com.github.sherter.jcon.examples.payless;

import com.github.sherter.jcon.InterceptableForwarder;
import com.github.sherter.jcon.networking.Reactor;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.projectfloodlight.openflow.protocol.*;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An adapted version of PayLess, implemented as an intermediate layer that caches stats reply
 * messages and answers stats requests with the cached replies
 */
public class Payless {

  static final Logger log = LoggerFactory.getLogger(Payless.class);

  final Multimap<Long, OFStatsReply> replies = HashMultimap.create();

  final Multimap<Match, OFFlowStatsEntry> flowStatEntries = HashMultimap.create();
  final Map<Long, Match> requestMatches = new HashMap<>();

  public void receiveFromController(
      OFStatsRequest<?> request, InterceptableForwarder.Context context) {
    OFStatsType type = request.getStatsType();
    if (type == OFStatsType.FLOW) {
      OFFlowStatsRequest flowReq = (OFFlowStatsRequest) request;
      requestMatches.put(flowReq.getXid(), flowReq.getMatch());
      Match match = flowReq.getMatch();
      Collection<OFFlowStatsEntry> entries = flowStatEntries.get(match);
      if (entries.size() != 0) {
        OFStatsReply response =
            OFFactories.getFactory(request.getVersion())
                .buildFlowStatsReply()
                .setXid(request.getXid())
                .setEntries(new ArrayList<>(entries))
                .build();
        context.sendUpstream(response);
        flowStatEntries.removeAll(match); // every second request answered by payless
        log.info("payless -> controller: {}", response);
        return;
      }
    }
    log.info("controller -> switch: {}", request);
    context.sendDownstream(request);
  }

  public void receiveFromSwitch(OFStatsReply reply, InterceptableForwarder.Context context) {
    log.info("switch -> controller: {}", reply);
    if (reply.getStatsType() == OFStatsType.FLOW) {
      Match requestMatch = requestMatches.remove(reply.getXid());
      OFFlowStatsReply flowReply = (OFFlowStatsReply) reply;
      flowStatEntries.putAll(requestMatch, flowReply.getEntries());
    }
    replies.put(reply.getXid(), reply);
    context.sendUpstream(reply);
  }

  public static void main(String[] args) throws IOException, InterruptedException {
    Payless payless = new Payless();
    Reactor reactor = new Reactor(Selector.open());
    InterceptableForwarder interceptor =
        new InterceptableForwarder.Builder(
                reactor, new InetSocketAddress(InetAddress.getLoopbackAddress(), 16653))
            .interceptDownstream(OFStatsRequest.class, payless::receiveFromController)
            .interceptUpstream(OFStatsReply.class, payless::receiveFromSwitch)
            .build();
    interceptor.listenOn(new InetSocketAddress(InetAddress.getLoopbackAddress(), 6653));
    reactor.loop();
  }
}
