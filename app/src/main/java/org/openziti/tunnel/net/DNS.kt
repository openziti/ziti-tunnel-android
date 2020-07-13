/*
 * Copyright (c) 2020 NetFoundry. All rights reserved.
 */

package org.openziti.tunnel.net

import org.openziti.net.dns.DNSResolver
import org.pcap4j.packet.DnsPacket
import org.pcap4j.packet.DnsRDataA
import org.pcap4j.packet.DnsRDataAaaa
import org.pcap4j.packet.DnsResourceRecord
import org.pcap4j.packet.namednumber.DnsOpCode
import org.pcap4j.packet.namednumber.DnsRCode
import org.pcap4j.packet.namednumber.DnsResourceRecordType
import org.pcap4j.util.ByteArrays
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

class DNS(val dnsResolver: DNSResolver) {

    fun resolve(packet: DnsPacket): DnsPacket {

        val responses = mutableListOf<DnsResourceRecord>()
        packet.header.questions.forEach {
            when(it.qType) {
                DnsResourceRecordType.A -> {
                    val resp = DnsResourceRecord.Builder()
                            .dataType(it.qType)
                            .dataClass(it.qClass)
                            .name(it.qName)
                            .ttl(30)
                            .rdLength(ByteArrays.INET4_ADDRESS_SIZE_IN_BYTES.toShort())


                    val ip = dnsResolver.resolve(it.qName.name) ?: bypassDNS(it.qName.name, it.qType)

                    if (ip != null) {
                        val rdata = DnsRDataA.Builder()
                                .address(ip as Inet4Address).build()
                        resp.rData(rdata)
                    }

                    responses.add(resp.build())
                }

                DnsResourceRecordType.AAAA -> {
                    val resp = DnsResourceRecord.Builder()
                            .dataType(it.qType)
                            .dataClass(it.qClass)
                            .name(it.qName)
                            .ttl(30)
                            .rdLength(ByteArrays.INET6_ADDRESS_SIZE_IN_BYTES.toShort())


                    val ip = bypassDNS(it.qName.name, it.qType)

                    if (ip != null) {
                        val rdata = DnsRDataAaaa.Builder()
                                .address(ip as Inet6Address).build()
                        resp.rData(rdata)
                    }

                    responses.add(resp.build())
                }

                else -> {}
            }
        }

        val resp = DnsPacket.Builder()
                .id(packet.header.id)
                .response(true)
                .opCode(DnsOpCode.QUERY)
                .rCode(DnsRCode.NO_ERROR)
                .qdCount(packet.header.qdCount)
                .questions(packet.header.questions)
                .anCount(responses.size.toShort())
                .answers(responses).build()

        return resp
    }

    private fun bypassDNS(name: String, type: DnsResourceRecordType): InetAddress? {

        try {
            for (a in InetAddress.getAllByName(name)) {
                if (type == DnsResourceRecordType.A && a is Inet4Address) return a
                if (type == DnsResourceRecordType.AAAA && a is Inet6Address) return a
            }
        } catch(ignored: UnknownHostException) {

        }

        return null
    }
}