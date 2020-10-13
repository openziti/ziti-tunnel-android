/*
 * Copyright (c) 2020 NetFoundry. All rights reserved.
 */

package org.openziti.mobile.net

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.openziti.Ziti
import org.openziti.ZitiConnection
import org.openziti.mobile.net.tcp.TCP
import org.openziti.mobile.net.tcp.TCPConn
import org.pcap4j.packet.*
import org.pcap4j.packet.namednumber.IpNumber
import org.pcap4j.packet.namednumber.IpV4TosPrecedence
import org.pcap4j.packet.namednumber.IpV4TosTos
import org.pcap4j.packet.namednumber.IpVersion
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.nio.ByteBuffer
import kotlin.coroutines.CoroutineContext

/**
 *
 */
class ZitiTunnelConnection(synPack: IpV4Packet,
                           val onInbound: (ByteBuffer) -> Unit) : CoroutineScope {

    val supervisor = SupervisorJob()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + supervisor

    lateinit var conn: ZitiConnection
    lateinit var tcpConn: TCPConn

    val peerIp: InetAddress
    val dstIp: InetAddress
    val tos = IpV4Rfc1349Tos.Builder()
            .mbz(false)
            .tos(IpV4TosTos.DEFAULT)
            .precedence(IpV4TosPrecedence.ROUTINE)
            .build()

    val info: String

    val state: TCP.State
        get() = if (::tcpConn.isInitialized) tcpConn.state else TCP.State.Closed

    init {
        peerIp = synPack.header.srcAddr
        dstIp = synPack.header.dstAddr

        val tcp = synPack.payload as TcpPacket

        val dstAddr = InetSocketAddress(synPack.header.dstAddr, tcp.header.dstPort.valueAsInt())

        info = "tcp:${peerIp.hostAddress}:${tcp.header.srcPort.valueAsInt()} -> ${dstIp.hostAddress}:${tcp.header.dstPort.valueAsInt()}"
        launch {
            val acks = mutableListOf<TcpPacket>()
            tcpConn = TCPConn(TUNNEL_MTU, synPack.header.srcAddr, synPack.header.dstAddr, tcp, acks)
            try {
                conn = Ziti.connect(dstAddr)

                readZitiConn()

                Log.i(info, "started reading from ziti backend")
            } catch (ex: Exception) {
                Log.e(info, "failed to connect to ziti backend service", ex)
                acks.clear()
                tcpConn.state = TCP.State.Closed
                acks.add(rejectTcpConnection(synPack).build())
            }
            sendToPeer(acks)
        }
    }


    fun readZitiConn() = launch {
        val buf = ByteArray(1024 * 16)
        var done = false
        try {
            conn.timeout = -1
            while (!done) {
                val read = conn.receive(buf, 0, buf.size)
                if (read > 0) {
                    Log.v(info, "from ziti backend ${String(buf,0,read)}")
                    val copyOf = buf.copyOf(read)
                    val packet = tcpConn.toPeer(copyOf)
                    sendToPeer(listOf(packet))
                } else if (read < 0) {
                    done = true
                    Log.d(info, "ziti conn is closed")
                }
            }

            tcpConn.close()?.let {
                sendToPeer(listOf(it))
            }

        } catch (psex: SocketException) {
            Log.w(info, "peer exception", psex)
        } catch (ex: Throwable) {
            Log.wtf(info, "ziti socket ex", ex)
        } finally {
            tcpConn.close()?.let {
                sendToPeer(listOf(it))
            }
            closeOutbound()
        }
    }

    fun isClosed() = conn.isClosed() && tcpConn.isClosed()

    fun process(packet: IpV4Packet) {
        val out = mutableListOf<TcpPacket>()
        val payload = tcpConn.fromPeer(packet.payload as TcpPacket, out)

        payload?.let {
            Log.v(info, "sending ${it.size} bytes to ziti backend\n${String(it)}")
            conn.write(it)
        }

        if (tcpConn.state in arrayOf(TCP.State.LAST_ACK, TCP.State.Closed)) {
            conn.close()
        }

        sendToPeer(out)
    }

    fun closeOutbound() {
        conn.close()
        supervisor.cancel()
        Log.d(info, "ziti conn is closing")
    }

    override fun toString(): String = info

    fun sendToPeer(packets: List<Packet>) {
        for (p in packets) {
            val ipPack = IpV4Packet.Builder()
                    .version(IpVersion.IPV4)
                    .protocol(IpNumber.TCP)
                    .tos(tos)
                    .srcAddr(dstIp as Inet4Address)
                    .dstAddr(peerIp as Inet4Address)
                    .correctChecksumAtBuild(true)
                    .correctLengthAtBuild(true)
                    .payloadBuilder(SimpleBuilder(p)).build()

            val data = ipPack.rawData
            Log.d(info, "sending to peer packet_size[${data.size}]")
            onInbound(ByteBuffer.wrap(data))
        }
    }
}