/*
 * Copyright (c) 2020 NetFoundry. All rights reserved.
 */

package org.openziti.mobile.net

import org.pcap4j.packet.IpV4Packet
import org.pcap4j.packet.IpV4Rfc1349Tos
import org.pcap4j.packet.TcpPacket
import org.pcap4j.packet.namednumber.IpV4TosPrecedence
import org.pcap4j.packet.namednumber.IpV4TosTos
import java.net.InetSocketAddress
import java.nio.ByteBuffer

const val TUNNEL_MTU = 32 * 1024

typealias ConnectionKey = Pair<InetSocketAddress, InetSocketAddress>

val TOS = IpV4Rfc1349Tos.Builder()
        .mbz(false)
        .tos(IpV4TosTos.DEFAULT)
        .precedence(IpV4TosPrecedence.ROUTINE)
        .build()

interface PacketRouter {
    fun route(b: ByteBuffer)
    fun stop()
}

fun TcpPacket.ack() = if (header.ack) "ACK" else null
fun TcpPacket.syn() = if (header.syn) "SYN" else null
fun TcpPacket.fin() = if (header.fin) "FIN" else null
fun TcpPacket.rst() = if (header.rst) "RST" else null

fun TcpPacket.flags() = listOf(syn(),ack(),rst(),fin()).filterNotNull().joinToString(separator = ",")

fun rejectTcpConnection(packet: IpV4Packet): TcpPacket.Builder {
    val tcpPacket = packet.payload as TcpPacket

    return TcpPacket.Builder()
            .dstAddr(packet.header.srcAddr)
            .srcAddr(packet.header.dstAddr)
            .dstPort(tcpPacket.header.srcPort)
            .srcPort(tcpPacket.header.dstPort)
            .ack(true)
            .acknowledgmentNumber(tcpPacket.header.sequenceNumber + 1)
            .rst(true)
            .correctChecksumAtBuild(true)
            .correctLengthAtBuild(true)
}