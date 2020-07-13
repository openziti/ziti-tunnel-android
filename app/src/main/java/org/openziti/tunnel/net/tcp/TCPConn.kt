/*
 * Copyright (c) 2020 NetFoundry. All rights reserved.
 */

package org.openziti.tunnel.net.tcp

import android.util.Log
import kotlinx.coroutines.channels.Channel
import org.openziti.tunnel.net.flags
import org.pcap4j.packet.*
import org.pcap4j.packet.namednumber.TcpPort
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.properties.Delegates

class TCPConn(mtu: Int, val peerIp: InetAddress, val dstIp: InetAddress, msg: TcpPacket, out: MutableList<TcpPacket>) {

    // static config
    val peerPort: TcpPort
    val dstPort: TcpPort

    val windowScale: Int
    val sackPermitted: Boolean
    val localWindow = 0xFFFF.toShort()
    val localWindowScale = 7.toByte()

    val info: String

    override fun toString() = info

    var state by Delegates.observable(TCP.State.LISTEN) { _, oldV, newV ->
        Log.d("tcp-conn", "$this/$oldV transitioning to $newV")
    }

    // TCP state
    //var state: TCP.State = TCP.State.LISTEN
    val peerSeq = AtomicLong()
    val localSeq = AtomicLong(0)
    val peerAck = AtomicLong(0)
    val peerWindow = AtomicInteger()
    val peerTimestamp = AtomicInteger()

    // channel to signal new ack has been received
    // used to re-evaluate peer window
    val ackSignal = Channel<Unit>()

    init {
        assert(msg.header.syn)

        peerPort = msg.header.srcPort
        dstPort = msg.header.dstPort

        peerWindow.set(msg.header.window.toInt())
        peerSeq.set(msg.header.sequenceNumberAsLong)

        var sp = false
        var ws: Byte = 0
        for (o in msg.header.options) {
            when (o) {
                is TcpNoOperationOption -> {}
                is TcpSackPermittedOption -> sp = true
                is TcpWindowScaleOption -> ws = o.shiftCount
                is TcpMaximumSegmentSizeOption -> {}
                is TcpTimestampsOption -> {
                    peerTimestamp.set(o.tsValue)
                }
                else -> {
                    error("unhanled TCP option $o")
                }
            }
        }

        windowScale = maxOf(ws.toInt(), 14)
        sackPermitted = sp
        state = TCP.State.SYN_RCVD
        info = "tcp:${peerIp.hostAddress}:${peerPort.valueAsString()} -> ${dstIp.hostAddress}:${dstPort.valueAsString()}"

        val synAck = TcpPacket.Builder()
                .srcAddr(dstIp)
                .srcPort(dstPort)
                .dstAddr(peerIp)
                .dstPort(peerPort)
                .syn(true)
                .ack(true)
                .window(localWindow)
                .sequenceNumber(localSeq.getAndIncrement().toInt())
                .acknowledgmentNumber(peerSeq.incrementAndGet().toInt())
                .correctChecksumAtBuild(true)
                .correctLengthAtBuild(true)
                .options(
                        mutableListOf(
                                TcpMaximumSegmentSizeOption.Builder()
                                        .maxSegSize((mtu - 40).toShort())
                                        .correctLengthAtBuild(true)
                                        .build(),
                                TcpNoOperationOption.getInstance(),
                                TcpNoOperationOption.getInstance(),

                                TcpWindowScaleOption.Builder()
                                        .correctLengthAtBuild(true)
                                        .shiftCount(localWindowScale)
                                        .build(),

                                TcpNoOperationOption.getInstance(),

                                TcpTimestampsOption.Builder()
                                        .tsEchoReply(peerTimestamp.get())
                                        .tsValue(getTSval())
                                        .length(10)
                                        .build()
                        )
                )

        out.add(synAck.build())
    }

    fun makePacket(ackInc: Int, syn: Boolean = false, fin: Boolean = false, payload: ByteArray = byteArrayOf()) = TcpPacket.Builder()
            .srcAddr(dstIp)
            .srcPort(dstPort)
            .dstAddr(peerIp)
            .dstPort(peerPort)
            .syn(syn)
            .fin(fin)
            .ack(true)
            .window(localWindow)
            .sequenceNumber(localSeq.getAndAdd(if (syn || fin) 1 else payload.size.toLong()).toInt())
            .acknowledgmentNumber(peerSeq.addAndGet(ackInc.toLong()).toInt())
            .payloadBuilder(
                    if (payload.isNotEmpty())
                        UnknownPacket.Builder().rawData(payload)
                    else
                        null
            )
            .options(
                    listOf(
                            TcpNoOperationOption.getInstance(),
                            TcpNoOperationOption.getInstance(),
                            TcpTimestampsOption.Builder()
                                    .tsEchoReply(peerTimestamp.get())
                                    .tsValue(getTSval())
                                    .length(10)
                                    .build()
                    )
            )
            .correctChecksumAtBuild(true)
            .correctLengthAtBuild(true).build().also {
                Log.v(info, "made packet[${it.length()}] flags[${it.flags()}] seq[${it.header.sequenceNumberAsLong}] ack[${it.header.acknowledgmentNumberAsLong}] data[${it.payload?.length()}]")
            }


    fun fromPeer(msg: TcpPacket, out: MutableList<TcpPacket>): ByteArray? {
        Log.v(info, "received flags[${msg.flags()}] seq[${msg.header.sequenceNumberAsLong}] ack[${msg.header.acknowledgmentNumberAsLong}] ws[${msg.header.windowAsInt} (${msg.header.windowAsInt shl windowScale})]")

        // update peer info
        peerWindow.set(msg.header.windowAsInt)
        if (msg.header.ack) {
            peerAck.set(msg.header.acknowledgmentNumberAsLong)
            ackSignal.offer(Unit)
        }

        for (opt in msg.header.options) {
            when (opt) {
                is TcpTimestampsOption -> peerTimestamp.set(opt.tsValue)
                else -> {}
            }
        }


        val dataLen = msg.payload?.length() ?: 0
        when (state) {

            // connection establishment

            TCP.State.SYN_RCVD -> {
                if (msg.header.ack) state = TCP.State.ESTABLISHED
                else if (msg.header.rst) state = TCP.State.LISTEN
                else Log.e(info, "invalid packet for state[$state]: ${msg.header}")
            }

            // Data transfer
            TCP.State.ESTABLISHED -> {
                if (msg.header.fin) {
                    state = TCP.State.CLOSE_WAIT
                    if (dataLen > 0) {
                        out.add(makePacket(dataLen))
                    }
                    out.add(makePacket(1, fin = true))
                    state = TCP.State.LAST_ACK
                } else if (msg.header.rst) {
                    state = TCP.State.Closed
                    Log.e(info, "peer connection reset")
                } else {
                    msg.payload?.let {
                        Log.v(info, "peer sent ${it.rawData.size} bytes")
                        out.add(makePacket(it.length()))
                    }
                }
            }

            TCP.State.LAST_ACK -> {
                if (msg.header.ack || msg.header.rst)
                    state = TCP.State.Closed
            }

            // ziti backend disconnected first
            TCP.State.FIN_WAIT_1 -> {
                if (msg.header.fin && msg.header.ack) {
                    out.add(makePacket(dataLen + 1))
                    state = TCP.State.TIME_WAIT
                } else if (msg.header.fin) {
                    out.add(makePacket(dataLen + 1))
                    state = TCP.State.CLOSING
                } else if (msg.header.ack) {
                    state = TCP.State.FIN_WAIT_2
                    out.add(makePacket(dataLen))
                }
            }

            TCP.State.FIN_WAIT_2 -> {
                if (msg.header.fin) {
                    out.add(makePacket(1))
                    state = TCP.State.TIME_WAIT
                }
            }

            TCP.State.CLOSING -> {
                if (msg.header.ack)
                    state = TCP.State.TIME_WAIT
            }

            else -> TODO("$state: ${msg.header}")
        }

        return msg.payload?.rawData
    }

    suspend fun toPeer(data: ByteArray): TcpPacket {
        Log.v(info, "got from ${data.size} from ziti")

        while (localSeq.get() + data.size > peerAck.get() + (peerWindow.get() shl windowScale)) {
            Log.v(info, "blocking until peer catches up")
            ackSignal.receive()
        }
        return makePacket(0, payload = data)
    }

    private fun getTSval(): Int {
        return (System.currentTimeMillis()/1000).toInt()
    }

    fun close(): TcpPacket? =
        when (state) {
            TCP.State.ESTABLISHED, TCP.State.SYN_RCVD -> {
                state = TCP.State.FIN_WAIT_1
                makePacket(0, syn = false, fin = true)
            }
            else -> {
                Log.d(info, "got close() in $state")
                null
            }
        }

    fun isClosed() = state == TCP.State.Closed || state == TCP.State.TIME_WAIT
}
