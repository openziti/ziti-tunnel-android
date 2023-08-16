/*
 * Copyright (c) 2020 NetFoundry. All rights reserved.
 */

package org.openziti.mobile.net

import android.util.Log
import org.openziti.net.dns.DNSResolver
import org.pcap4j.packet.DnsPacket
import org.pcap4j.packet.IpPacket
import org.pcap4j.packet.IpV4Packet
import org.pcap4j.packet.IpV6Packet
import org.pcap4j.packet.SimpleBuilder
import org.pcap4j.packet.TcpPacket
import org.pcap4j.packet.UdpPacket
import org.pcap4j.packet.namednumber.IpNumber
import java.net.Inet4Address
import java.net.Inet6Address


class ZitiNameserver(resolver: DNSResolver) {

    val addresses = setOf(defaultIPv4Address, defaultIPv6Address)
    private val dns = DNS(resolver)

    fun process(packet: IpPacket): IpPacket? {

        when (packet.header.protocol) {
            IpNumber.UDP -> {}
            IpNumber.TCP -> return rejectTCP(packet)

            IpNumber.ICMPV4,IpNumber.ICMPV6 -> {
                Log.v(TAG, "ignoring received ${packet.payload.header}")
                return null
            }

            else -> {
                Log.wtf(TAG, "WTF is this? ${packet.payload.header}")
                return null
            }
        }

        val udpPacket = packet.payload as UdpPacket
        val dnsPacket = udpPacket.payload as DnsPacket

        val dnsResp = dns.resolve(dnsPacket)

        val respUDP = UdpPacket.Builder()
            .dstAddr(packet.header.srcAddr)
            .srcAddr(packet.header.dstAddr)
            .dstPort(udpPacket.header.srcPort)
            .srcPort(udpPacket.header.dstPort)
            .correctChecksumAtBuild(true)
            .correctLengthAtBuild(true)
            .payloadBuilder(SimpleBuilder(dnsResp))

        val resp = packet.builder.payloadBuilder(respUDP)

        when (resp) {
            is IpV4Packet.Builder -> {
                resp.dstAddr(packet.header.srcAddr as Inet4Address)
                resp.srcAddr(packet.header.dstAddr as Inet4Address)
                resp.correctChecksumAtBuild(true)
                resp.correctLengthAtBuild(true)
            }
            is IpV6Packet.Builder -> {
                resp.dstAddr(packet.header.srcAddr as Inet6Address)
                resp.srcAddr(packet.header.dstAddr as Inet6Address)
                resp.correctLengthAtBuild(true)
            }
        }

        return resp.build() as IpPacket
    }

    private fun rejectTCP(packet: IpPacket): IpPacket {
        val tcpPacket = packet.payload as TcpPacket

        Log.w(TAG, "closing attempted TCP connection: ${tcpPacket.header.dstPort}")
        val tcpOut = TcpPacket.Builder()
            .dstAddr(packet.header.srcAddr)
            .srcAddr(packet.header.dstAddr)
            .dstPort(tcpPacket.header.srcPort)
            .srcPort(tcpPacket.header.dstPort)
            .ack(true)
            .acknowledgmentNumber(tcpPacket.header.sequenceNumber + 1)
            .rst(true)
            .correctChecksumAtBuild(true)
            .correctLengthAtBuild(true)

        val outIp = packet.builder.apply {
            when(this) {
                is IpV4Packet.Builder -> {
                    dstAddr(packet.header.srcAddr as Inet4Address)
                    srcAddr(packet.header.dstAddr as Inet4Address)
                    correctChecksumAtBuild(true)
                    correctLengthAtBuild(true)
                }
                is IpV6Packet.Builder -> {
                    dstAddr(packet.header.srcAddr as Inet6Address)
                    srcAddr(packet.header.dstAddr as Inet6Address)
                    correctLengthAtBuild(true)
                }
            }
            payloadBuilder(tcpOut)
        }

        return outIp.build() as IpPacket
    }
    companion object {
        const val TAG = "ziti-dns"

        val defaultIPv4Address = Inet4Address.getByAddress(byteArrayOf(100, 64, 0, 2))
        val defaultIPv6Address = Inet6Address.getByAddress(
            byteArrayOf(
                0xFDu.toByte(), 0,                             // ULA prefix
                *("ziti".toByteArray(Charsets.US_ASCII)),
                0, 0,// uniq Global ID
                0, 0, 0, 0,
                0, 0, 0, 2
            )
        )

    }
}