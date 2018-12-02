package com.github.sherter.jcon.networking

import spock.lang.Specification

import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.util.function.Consumer
import java.util.function.Function

class ReactorSpec extends Specification {

    def 'callbacks are called as expected when listening and establishing new connections'() {
        setup:
        def connectedCounter = 0
        def disconnectedCounter = 0
        def receivedData = [:]

        def reactor = new Reactor(Selector.open())

        def connectedCallback = { handler ->
            connectedCounter++
        } as Consumer

        def callbacksFactory = { handler ->
            new Handler.Callbacks(
                    { data ->
                        if (!receivedData.containsKey(handler)) {
                            receivedData[handler] = new ArrayList()
                        }
                        receivedData[handler].add(data)
                    } as Consumer,

                    { throwable ->
                        disconnectedCounter++
                    } as Consumer)
        } as Function

        ServerSocketChannel socketChannel = reactor.listen(null, callbacksFactory, connectedCallback)
        def handler1 = reactor.establish(socketChannel.localAddress, callbacksFactory)
        def handler2 = reactor.establish(socketChannel.localAddress, callbacksFactory)
        def handler3 = reactor.establish(socketChannel.localAddress, callbacksFactory)

        def handler1Data = [[1, 2, 3], [4, 5, 6]] as byte[][]
        def handler2Data = [[7, 8, 9], [10, 11], [12]] as byte[][]

        when:
        def thread = Thread.start { reactor.loop() }
        handler1Data.each {
            handler1.send(it)
            sleep(100) // "prevent" accumulating data
        }
        handler2Data.each {
            handler2.send(it)
            sleep(100)
        }
        // closes sending (this) and receiving handler (created server side); increases disconnect count by 2
        handler3.close()

        sleep(1000)
        thread.interrupt()
        thread.join()

        then:
        connectedCounter == 3
        disconnectedCounter == 2
        receivedData.each { k, v ->
            assert v == handler1Data || v == handler2Data
        }
    }
}
