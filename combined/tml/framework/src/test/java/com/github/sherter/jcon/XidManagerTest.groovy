package com.github.sherter.jcon

import org.projectfloodlight.openflow.protocol.OFFactories
import org.projectfloodlight.openflow.protocol.OFFactory
import org.projectfloodlight.openflow.protocol.OFVersion
import spock.lang.Specification

class XidManagerTest extends Specification {

    OFFactory builder = OFFactories.getFactory(OFVersion.OF_13)

    def "request from upstream is answered with same xid response"() {
        given:
        XidManager manager = new XidManager()
        def requestAtController = builder.buildEchoRequest().setXid(5).build()

        when:
        def intermediate = manager.toInternalFromUpstream(requestAtController)
        intermediate = manager.internalToDownstream(intermediate)

        def replyAtSwitch = builder.buildEchoReply().setXid(intermediate.getXid()).build()
        intermediate = manager.toInternalFromDownstream(replyAtSwitch)

        def replyAtController = manager.internalToUpstream(intermediate)

        then:
        requestAtController.xid == replyAtController.xid
    }

    def "request from downstream is answered with same xid response"() {
        given:
        XidManager manager = new XidManager()
        def requestAtSwitch = builder.buildEchoRequest().setXid(5).build()

        when:
        def intermediate = manager.toInternalFromDownstream(requestAtSwitch)
        intermediate = manager.internalToUpstream(intermediate)

        def replyAtController = builder.buildEchoReply().setXid(intermediate.getXid()).build()
        intermediate = manager.toInternalFromUpstream(replyAtController)

        def replyAtSwitch = manager.internalToDownstream(intermediate)

        then:
        requestAtSwitch.xid == replyAtSwitch.xid
    }

    def "request from controller and new internal request"() {
        given:
        def manager = new XidManager()
        def requestAtController = builder.buildEchoRequest().setXid(1).build()

        when:
        def controllerRequestAtInternal = manager.toInternalFromUpstream(requestAtController)
        def internalRequestAtInternal = builder.buildEchoRequest().setXid(1).build()

        def controllerRequestAtSwitch = manager.internalToDownstream(controllerRequestAtInternal)
        def internalRequestAtSwitch = manager.internalToDownstream(internalRequestAtInternal)

        def responseToControllerAtSwitch = builder.buildEchoReply().setXid(controllerRequestAtSwitch.getXid()).build()
        def responseToInternalAtSwitch = builder.buildEchoReply().setXid(internalRequestAtSwitch.getXid()).build()

        def responseToControllerAtInternal = manager.toInternalFromDownstream(responseToControllerAtSwitch)
        def responseToInternalAtInternal = manager.toInternalFromDownstream(responseToInternalAtSwitch)

        def responseToControllerAtController = manager.internalToUpstream(responseToControllerAtInternal)

        then:
        requestAtController.xid == responseToControllerAtController.xid
        internalRequestAtInternal.xid == responseToInternalAtInternal.xid

        when:
        manager.internalToUpstream(responseToInternalAtInternal)

        then:
        thrown(Exception)
    }

}
