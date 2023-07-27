/*
 * Copyright (c) 2020 NetFoundry. All rights reserved.
 */

package org.openziti.mobile.net

import android.util.Log
import org.openziti.net.dns.DNSResolver
import org.pcap4j.packet.DnsPacket
import org.pcap4j.packet.IpPacket
import org.pcap4j.packet.IpV4Packet
import org.pcap4j.packet.Packet
import org.pcap4j.packet.SimpleBuilder
import org.pcap4j.packet.TcpPacket
import org.pcap4j.packet.UdpPacket
import org.pcap4j.packet.namednumber.IpNumber
import org.pcap4j.packet.namednumber.IpVersion
import java.net.Inet4Address
import java.net.Inet6Address


class ZitiNameserver(resolver: DNSResolver) {

    val addresses = setOf(defaultIPv4Address)
    val dns = DNS(resolver)

    fun process(packet: IpPacket): IpPacket? {

        if (packet.header.version != IpVersion.IPV4) {
            Log.w(TAG, "not handling ${packet.header.version} at this time")
            return null
        }

        val ipv4 = packet as IpV4Packet

        val payload: Packet.Builder? = when (ipv4.header.protocol) {
            IpNumber.UDP -> {
                val udpPacket = packet.payload as UdpPacket
                if (udpPacket.payload is DnsPacket) {

                    val resp = dns.resolve(udpPacket.payload as DnsPacket)

                    UdpPacket.Builder()
                            .dstAddr(packet.header.srcAddr)
                            .srcAddr(packet.header.dstAddr)
                            .dstPort(udpPacket.header.srcPort)
                            .srcPort(udpPacket.header.dstPort)
                            .correctChecksumAtBuild(true)
                            .correctLengthAtBuild(true)
                            .payloadBuilder(SimpleBuilder(resp))

                } else null
            }

            IpNumber.TCP -> {
                val tcpPacket = ipv4.payload as TcpPacket
                Log.w(TAG, "closing attempted TCP connection: ${tcpPacket.header.dstPort}")
                TcpPacket.Builder()
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

            IpNumber.ICMPV4,IpNumber.ICMPV6 -> { // TODO?
                Log.v(TAG, "ignoring received ${ipv4.payload.header}")
                null
            }

            else -> {
                Log.wtf(TAG, "WTF is this? ${packet.payload.header}")
                null
            }
        }

        return payload?.let {
            IpV4Packet.Builder()
                    .version(IpVersion.IPV4)
                    .protocol(ipv4.header.protocol)
                    .tos(ipv4.header.tos)
                    .dstAddr(ipv4.header.srcAddr)
                    .srcAddr(ipv4.header.dstAddr)
                    .correctChecksumAtBuild(true)
                    .correctLengthAtBuild(true)
                    .payloadBuilder(it).build()
        }
    }
    companion object {
        const val TAG = "ziti-dns"

        val defaultIPv4Address = Inet4Address.getByAddress(byteArrayOf(100, 64, 0, 2))
        val defaultIPv6Address = Inet6Address.getByAddress(
            byteArrayOf(
                0xFDu.toByte(),                                 // ULA prefix
                *("ziti!".toByteArray(Charsets.US_ASCII)),
                0, 0,// uniq Global ID
                0, 0, 0, 0,
                0, 0, 0, 2
            )
        )

    }
}