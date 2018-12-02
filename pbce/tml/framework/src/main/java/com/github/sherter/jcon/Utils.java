package com.github.sherter.jcon;

import static org.projectfloodlight.openflow.protocol.OFType.*;

import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import org.projectfloodlight.openflow.protocol.*;
import org.projectfloodlight.openflow.protocol.OFType;

public class Utils {
  public static final ImmutableBiMap<OFType, Class<?>> MESSAGE_TYPE_CLASSES =
      ImmutableBiMap.<OFType, Class<?>>builder()
          .put(OFType.HELLO, OFHello.class)
          .put(OFType.ERROR, OFErrorMsg.class)
          .put(OFType.ECHO_REQUEST, OFEchoRequest.class)
          .put(OFType.ECHO_REPLY, OFEchoReply.class)
          .put(OFType.EXPERIMENTER, OFExperimenter.class)
          .put(OFType.FEATURES_REQUEST, OFFeaturesRequest.class)
          .put(OFType.FEATURES_REPLY, OFFeaturesReply.class)
          .put(OFType.GET_CONFIG_REQUEST, OFGetConfigRequest.class)
          .put(OFType.GET_CONFIG_REPLY, OFGetConfigReply.class)
          .put(OFType.SET_CONFIG, OFSetConfig.class)
          .put(OFType.PACKET_IN, OFPacketIn.class)
          .put(OFType.FLOW_REMOVED, OFFlowRemoved.class)
          .put(OFType.PORT_STATUS, OFPortStatus.class)
          .put(OFType.PACKET_OUT, OFPacketOut.class)
          .put(OFType.FLOW_MOD, OFFlowMod.class)
          .put(OFType.PORT_MOD, OFPortMod.class)
          .put(OFType.STATS_REQUEST, OFStatsRequest.class)
          .put(OFType.STATS_REPLY, OFStatsReply.class)
          .put(OFType.BARRIER_REQUEST, OFBarrierRequest.class)
          .put(OFType.BARRIER_REPLY, OFBarrierReply.class)
          .put(OFType.QUEUE_GET_CONFIG_REQUEST, OFQueueGetConfigRequest.class)
          .put(OFType.QUEUE_GET_CONFIG_REPLY, OFQueueGetConfigReply.class)
          .put(OFType.GROUP_MOD, OFGroupMod.class)
          .put(OFType.TABLE_MOD, OFTableMod.class)
          .put(OFType.ROLE_REQUEST, OFRoleRequest.class)
          .put(OFType.ROLE_REPLY, OFRoleReply.class)
          .put(OFType.GET_ASYNC_REQUEST, OFAsyncGetRequest.class)
          .put(OFType.GET_ASYNC_REPLY, OFAsyncGetReply.class)
          .put(OFType.SET_ASYNC, OFAsyncSet.class)
          .put(OFType.METER_MOD, OFMeterMod.class)
          .put(OFType.ROLE_STATUS, OFRoleStatus.class)
          .put(OFType.TABLE_STATUS, OFTableStatus.class)
          .put(OFType.REQUESTFORWARD, OFRequestforward.class)
          .put(OFType.BUNDLE_CONTROL, OFBundleCtrlMsg.class)
          .put(OFType.BUNDLE_ADD_MESSAGE, OFBundleAddMsg.class)
          .put(OFType.CONTROLLER_STATUS, OFControllerStatus.class)
          .build();

  /** Message types sent from controller to switch. */
  public static final ImmutableSet<OFType> DOWNSTREAM_TYPES =
      Sets.immutableEnumSet(
          OFType.FEATURES_REQUEST,
          OFType.GET_CONFIG_REQUEST,
          OFType.SET_CONFIG,
          OFType.PACKET_OUT,
          OFType.FLOW_MOD,
          OFType.GROUP_MOD,
          OFType.PORT_MOD,
          OFType.TABLE_MOD,
          OFType.STATS_REQUEST,
          OFType.BARRIER_REQUEST,
          OFType.ROLE_REQUEST,
          OFType.GET_ASYNC_REQUEST,
          OFType.SET_ASYNC,
          OFType.METER_MOD,
          OFType.BUNDLE_CONTROL,
          OFType.BUNDLE_ADD_MESSAGE,
          // symmetric messages
          OFType.HELLO,
          OFType.ERROR,
          OFType.ECHO_REQUEST,
          OFType.ECHO_REPLY,
          OFType.EXPERIMENTER);

  /** Message types sent from switch to controller. */
  public static final ImmutableSet<OFType> UPSTREAM_TYPES =
      Sets.immutableEnumSet(
          OFType.PACKET_IN,
          OFType.FLOW_REMOVED,
          OFType.PORT_STATUS,
          OFType.ROLE_STATUS,
          OFType.TABLE_STATUS,
          OFType.REQUESTFORWARD,
          OFType.CONTROLLER_STATUS,
          OFType.FEATURES_REPLY,
          OFType.GET_CONFIG_REPLY,
          OFType.STATS_REPLY,
          OFType.BARRIER_REPLY,
          OFType.ROLE_REPLY,
          OFType.GET_ASYNC_REPLY,
          // symmetric messages
          OFType.HELLO,
          OFType.ERROR,
          OFType.ECHO_REQUEST,
          OFType.ECHO_REPLY,
          OFType.EXPERIMENTER);

  public static final ImmutableSet<Class<?>> DOWNSTREAM_CLASSES;
  public static final ImmutableSet<Class<?>> UPSTREAM_CLASSES;

  static {
    List<Class<?>> l = new ArrayList<>(DOWNSTREAM_TYPES.size());
    for (OFType t : DOWNSTREAM_TYPES) {
      l.add(MESSAGE_TYPE_CLASSES.get(t));
    }
    DOWNSTREAM_CLASSES = ImmutableSet.copyOf(l);
    l = new ArrayList<>(UPSTREAM_TYPES.size());
    for (OFType t : UPSTREAM_TYPES) {
      l.add(MESSAGE_TYPE_CLASSES.get(t));
    }
    UPSTREAM_CLASSES = ImmutableSet.copyOf(l);
  }

  private Utils() {}

  public static byte[] serialize(OFMessage message) {
    ByteBuf b = Unpooled.buffer();
    message.writeTo(b);
    byte[] data = new byte[b.writerIndex()];
    b.readBytes(data);
    b.release();
    return data;
  }
}
