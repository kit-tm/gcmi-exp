package com.github.sherter.jcon.composer;

import com.github.sherter.jcon.InterceptableForwarder;
import org.projectfloodlight.openflow.protocol.OFPacketOut;
import org.projectfloodlight.openflow.types.OFPort;

public class PacketOutPortChanger {

  void receivePacketOut(OFPacketOut packetOut, InterceptableForwarder.Context context) {
    OFPort inPort = packetOut.getInPort();
    OFPacketOut newPacketOut =
        packetOut.createBuilder().setInPort(OFPort.of(inPort.getPortNumber() + 1)).build();
    context.sendDownstream(newPacketOut);
  }

  PacketOutPortChanger registerWith(InterceptableForwarder.Builder builder) {
    builder.interceptDownstream(OFPacketOut.class, this::receivePacketOut);
    return this;
  }
}
