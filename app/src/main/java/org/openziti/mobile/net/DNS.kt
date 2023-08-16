/*
 * Copyright (c) 2020 NetFoundry. All rights reserved.
 */

package org.openziti.mobile.net

import org.openziti.net.dns.DNSResolver
import org.openziti.util.Logged
import org.openziti.util.ZitiLog
import org.pcap4j.packet.DnsPacket
import org.pcap4j.packet.DnsQuestion
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

class DNS(val dnsResolver: DNSResolver): Logged by ZitiLog() {

    fun resolve(packet: DnsPacket): DnsPacket {
        val q = packet.header.questions.firstOrNull()

        val resp = findAnswer(q)

        val rb = DnsPacket.Builder()
                .id(packet.header.id)
                .response(true)
                .opCode(DnsOpCode.QUERY)
                .qdCount(packet.header.qdCount)
                .questions(packet.header.questions)

        if(resp == null) {
            rb.rCode(DnsRCode.NX_DOMAIN)
                    .anCount(0)
                    .answers(emptyList())
        } else {
            rb.rCode(DnsRCode.NO_ERROR)
                    .anCount(1)
                    .answers(listOf(resp))
        }

        return rb.build()
    }

    private fun findAnswer(q: DnsQuestion?): DnsResourceRecord? {
        if (q == null) return null
        if (q.qType != DnsResourceRecordType.A && q.qType != DnsResourceRecordType.AAAA) return null

        // don't log since it may be sensitive, keep for debugging purposes
        // d { "resolving ${q.qType} ${q.qName}"}
        val addr = dnsResolver.resolve(q.qName.name) ?: bypassDNS(q.qName.name, q.qType) ?: return null
        // d { "resolved $q => $addr" }

        val answer = DnsResourceRecord.Builder()
            .dataType(q.qType)
            .dataClass(q.qClass)
            .name(q.qName)
            .ttl(30)

        if (q.qType == DnsResourceRecordType.A) {
            answer.rdLength(ByteArrays.INET4_ADDRESS_SIZE_IN_BYTES.toShort())

            val rdata = DnsRDataA.Builder()
                .address(addr as Inet4Address).build()
            answer.rData(rdata)
        } else if (q.qType == DnsResourceRecordType.AAAA) {
            val addr6 = mapToIPv6(addr)
            answer.rdLength(ByteArrays.INET6_ADDRESS_SIZE_IN_BYTES.toShort())
            answer.rData(DnsRDataAaaa.Builder().address(addr6).build())
        }

        return answer.build()
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