/*
 * Copyright (c) 2020 NetFoundry. All rights reserved.
 */

package org.openziti.mobile.net

import android.util.Log
import kotlinx.coroutines.runBlocking
import org.pcap4j.packet.IpPacket
import org.pcap4j.packet.IpSelector
import org.pcap4j.packet.IpV4Packet
import org.pcap4j.packet.IpV4Packet.IpV4Header
import org.pcap4j.packet.IpV6Packet.IpV6Header
import org.pcap4j.packet.TcpPacket
import org.pcap4j.packet.namednumber.IpNumber
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.Timer
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.timer


class PacketRouterImpl(val dnsServer: ZitiNameserver, val inbound: suspend (b: ByteBuffer) -> Unit) : PacketRouter {
    val TAG = "routing"
    val tcpConnections = ConcurrentHashMap<ConnectionKey, ZitiTunnelConnection>()

    val timer: Timer

    override fun route(b: ByteBuffer) {

        val packet = IpSelector.newPacket(b.array(), b.arrayOffset(), b.limit()) as? IpPacket

        if (packet == null) {
            Log.w(TAG, "unknown packet type")
            return
        }

        val header = packet.header
        val dstAddr = when (header) {
            is IpV4Header -> header.dstAddr
            is IpV6Header -> header.dstAddr
            else -> {
                Log.d(TAG, "dropping packet $header")
                return
            }
        }

        if (dstAddr in dnsServer.addresses) {
            dnsServer.process(packet)?.let {
                runBlocking { inbound(ByteBuffer.wrap(it.rawData)) }
            }

            return
        }

        if (packet !is IpV4Packet) {
            Log.w(TAG, "not handling ${packet.header} at this time")
            return
        }

        val protocol = packet.header.protocol

        when (protocol) {
            IpNumber.TCP -> routeTCP(packet)

            IpNumber.UDP -> processUDP(packet)

            else -> Log.w(TAG, "dropping unhandled packet ${packet.header}")
        }
    }

    private fun processUDP(packet: IpPacket) {
        Log.w(TAG, "UDP services are not currently supported ${packet.payload.header}")
    }

    private fun routeTCP(msg: IpV4Packet) {
        val tcp = msg.payload as TcpPacket

        val src = InetSocketAddress(msg.header.srcAddr, tcp.header.srcPort.valueAsInt())
        val dst = InetSocketAddress(msg.header.dstAddr, tcp.header.dstPort.valueAsInt())
        val key = Pair(src, dst)

        Log.v("routing", "got msg[$key]: ${msg.length()} bytes")

        val conn = tcpConnections[key]

        // new connection
        if (conn == null && tcp.header.syn) {
            val newConn = ZitiTunnelConnection(src, dst, msg, inbound)
            tcpConnections.put(key, newConn)
            Log.i(TAG, "created $newConn")
        } else if (conn != null) {
            conn.process(msg)
            if (conn.isClosed()) {
                tcpConnections.remove(key)
                Log.i(TAG, "removing ${conn.tcpConn.info}")
            }
        } else {
            Log.e(TAG, "invalid state. No connection found for [$key]. packet is dropped")
        }
    }

    override fun stop() {
        tcpConnections.forEach {
            it.value.shutdown()
        }
        timer.cancel()
    }

    init {
        timer = timer(name = "PacketRouter", period = 30000) {
            Log.d(TAG, "${tcpConnections.size} active connections")
            tcpConnections.forEach {
                Log.v(TAG, "${it.key}/${it.value.state}")
                if (it.value.isClosed()) {
                    tcpConnections.remove(it.key)
                }
            }
        }
    }
}