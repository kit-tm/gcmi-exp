package com.github.sherter.jcon

import com.github.sherter.jcon.networking.Handler
import com.github.sherter.jcon.networking.Reactor
import io.netty.buffer.Unpooled
import org.projectfloodlight.openflow.protocol.OFFactories
import org.projectfloodlight.openflow.protocol.OFFactory
import org.projectfloodlight.openflow.protocol.OFMessage
import org.projectfloodlight.openflow.protocol.OFVersion
import spock.lang.Specification

import java.nio.channels.Selector

class InterceptableForwarderSpec extends Specification {

    static final InetSocketAddress someAddress = new InetSocketAddress("0.0.0.0", 1234)
    static final Reactor someReactor = new Reactor(Selector.open())
    static final OFFactory messageBuilder = OFFactories.getFactory(OFVersion.OF_13)

    def "message is forwarded from controller to switch"() {
        given:
        def forwarder = new InterceptableForwarder.Builder(someAddress).build(someReactor)

        Handler controllerHandler = Mock()
        Handler switchHandler = Mock()
        InterceptableForwarder.ConnectionPairContext context = new InterceptableForwarder.ConnectionPairContext(switchHandler)
        context.setControllerHandler(controllerHandler)
        OFMessage received

        when:
        def req = messageBuilder.buildEchoRequest().setXid(123).build()
        forwarder.receivedFromController(req, context)

        then:
        1 * switchHandler.send(_,_) >> { args -> received = OFFactories.genericReader.readFrom(Unpooled.wrappedBuffer(args[0])) }

        when:
        def reply = messageBuilder.buildEchoReply().setXid(received.getXid()).build()
        forwarder.receivedFromSwitch(reply, context)

        then:
        1 * controllerHandler.send(_,_) >> { args -> received = OFFactories.genericReader.readFrom(Unpooled.wrappedBuffer(args[0])) }
        received.xid == 123
    }
}
