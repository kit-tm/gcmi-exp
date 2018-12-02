package com.github.sherter.jcon.examples.payless;

import com.github.sherter.jcon.InterceptableForwarder;
import com.github.sherter.jcon.networking.Reactor;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.Selector;
import java.util.*;
import java.util.concurrent.TimeUnit;
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

  private final long duration;
  private final TimeUnit unit;
  private Cache<Match, List<OFFlowStatsEntry>> stats;
  private Multimap<Match, OFFlowStatsRequest> pendingRequests = HashMultimap.create();
  private Map<Long, Match> pendingXids = new HashMap<>();

  public Payless(long duration, TimeUnit unit) {
    stats = CacheBuilder.newBuilder()
            .concurrencyLevel(4)
            .weakKeys()
            .maximumSize(10000)
            .expireAfterWrite(duration, unit)
            .build();
    this.duration = duration;
    this.unit = unit;
  }

  public void receiveFromController(
      OFStatsRequest<?> request, InterceptableForwarder.Context context) {
    OFStatsType type = request.getStatsType();
    if (type == OFStatsType.FLOW) {
      OFFlowStatsRequest flowReq = (OFFlowStatsRequest) request;

      List<OFFlowStatsEntry> previousStats = stats.getIfPresent(flowReq.getMatch());
      if (previousStats != null) {
        OFStatsReply response =
            OFFactories.getFactory(request.getVersion())
                .buildFlowStatsReply()
                .setXid(request.getXid())
                .setEntries(previousStats)
                .build();
        context.sendUpstream(response);
        log.info("payless -> controller: {}", response);
      } else {
        if (!pendingRequests.containsKey(flowReq.getMatch())) {
          context.sendDownstream(request);
          pendingXids.put(request.getXid(), flowReq.getMatch());
        }
        pendingRequests.put(flowReq.getMatch(), flowReq);
      }
    } else {
      // port stats or other
      log.info("controller: -> switch: {}", request);
      context.sendDownstream(request);
    }
  }

  public void receiveFromSwitch(OFStatsReply reply, InterceptableForwarder.Context context) {
    log.info("switch -> controller: {}", reply);
    if (reply.getStatsType() == OFStatsType.FLOW) {
      OFFlowStatsReply flowReply = (OFFlowStatsReply) reply;
      Match pendingRequestMatch = pendingXids.remove(flowReply.getXid());
      // answer all pending requests
      for (OFFlowStatsRequest request : pendingRequests.removeAll(pendingRequestMatch)) {
        OFStatsReply response =
            OFFactories.getFactory(request.getVersion())
                .buildFlowStatsReply()
                .setXid(request.getXid())
                .setEntries(flowReply.getEntries())
                .build();
        context.sendUpstream(response);
      }
      // put in cache for future requests
      stats.put(pendingRequestMatch, flowReply.getEntries());
    }
    context.sendUpstream(reply);
  }

  public static void main(String[] args) throws IOException, InterruptedException {
    Payless payless = new Payless(1, TimeUnit.SECONDS);
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
