/*
 * Copyright (c) 2020 NetFoundry. All rights reserved.
 */

package org.openziti.mobile.net

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import org.openziti.Ziti
import org.openziti.ZitiConnection
import org.openziti.mobile.net.tcp.TCP
import org.openziti.mobile.net.tcp.TCPConn
import org.openziti.net.nio.writeCompletely
import org.pcap4j.packet.IpV4Packet
import org.pcap4j.packet.Packet
import org.pcap4j.packet.SimpleBuilder
import org.pcap4j.packet.TcpPacket
import org.pcap4j.packet.namednumber.IpNumber
import org.pcap4j.packet.namednumber.IpVersion
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel
import kotlin.coroutines.CoroutineContext

/**
 *
 */
class ZitiTunnelConnection(val srcAddr: InetSocketAddress, val dstAddr: InetSocketAddress, synPack: IpV4Packet,
                           val onInbound: suspend (ByteBuffer) -> Unit) : CoroutineScope {

    val supervisor = SupervisorJob()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + supervisor

    val conn = async { Ziti.connect(dstAddr) }
    val tcpConn: TCPConn = TCPConn(TUNNEL_MTU, srcAddr, dstAddr)

    val peerIp: Inet4Address = srcAddr.address as Inet4Address
    val dstIp: Inet4Address = dstAddr.address as Inet4Address
    val info: String = "tcp:${srcAddr} -> ${dstAddr}"

    val state: TCP.State
        get() = tcpConn.state

    val inboundPackets = Channel<IpV4Packet>(64, BufferOverflow.DROP_LATEST) {
        Log.d(info, "failed to process $it")
    }

    init {
        start(synPack)
    }

    fun start(synPack: IpV4Packet) = launch {
        val tcp = synPack.payload as TcpPacket
        tcpConn.init(tcp)

        try {
            val c = withTimeout(3000) {
                conn.await()
            }
            sendToPeer(tcpConn.accept().toList())
            processPeerPackets(c as AsynchronousSocketChannel)
            readZitiConn(c)
        } catch (ex: Exception) {
            Log.e(info, "failed to connect to Ziti Service: $ex")
            sendToPeer(tcpConn.reject().toList())
            return@launch
        }
    }

    fun readZitiConn(conn: ZitiConnection) = launch {
        try {
            val buf = ByteArray(1024 * 16)
            var done = false
            try {
                conn.timeout = -1
                while (!done) {
                    val read = conn.receive(buf, 0, buf.size)
                    if (read > 0) {
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
            }
        } finally {
            Log.i(info, "readZitiConn() is finished")
        }
    }

    fun isClosed() = tcpConn.isClosed()

    fun processPeerPackets(zitiConn: AsynchronousSocketChannel) = launch {

        inboundPackets.receiveAsFlow().collect { packet ->
            val out = mutableListOf<TcpPacket>()
            val payload = tcpConn.fromPeer(packet.payload as TcpPacket, out)

            runCatching {
                payload?.let {
                    Log.v(info, "sending ${it.size} bytes to ziti backend")
                    zitiConn.writeCompletely(ByteBuffer.wrap(it))
                }
            }.onSuccess {
                when (tcpConn.state) {
                    TCP.State.LAST_ACK, TCP.State.Closed -> {
                        zitiConn.close()
                    }
                    TCP.State.FIN_WAIT_1 -> {
                        zitiConn.shutdownOutput()
                    }
                    else -> {
                    }
                }
            }.onFailure { exc ->
                Log.w(info, "failed to deliver date to Ziti side", exc)
                tcpConn.close()?.let { it ->
                    out.add(it)
                }
            }

            sendToPeer(out)
        }

        Log.i(info, "processPeerPackets() is done")
    }

    fun process(packet: IpV4Packet) = runBlocking {
        if (!inboundPackets.offer(packet)) {
           Log.w(info, "packet was dropped: queue is full")
        }
    }

    fun shutdown() {
        runBlocking { runCatching { conn.await().close() } }
        supervisor.cancel()
        Log.d(info, "ziti tunnel conn is closed")
    }

    override fun toString(): String = info

    suspend fun sendToPeer(packets: List<Packet>) {
        for (p in packets) {
            val ipPack = IpV4Packet.Builder()
                    .version(IpVersion.IPV4)
                    .protocol(IpNumber.TCP)
                    .tos(TOS)
                    .srcAddr(dstIp)
                    .dstAddr(peerIp)
                    .correctChecksumAtBuild(true)
                    .correctLengthAtBuild(true)
                    .payloadBuilder(SimpleBuilder(p)).build()

            val data = ipPack.rawData
            Log.d(info, "sending to peer packet_size[${data.size}]")
            onInbound(ByteBuffer.wrap(data))
        }
    }
}