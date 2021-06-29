/*
 * Copyright (c) 2020 NetFoundry. All rights reserved.
 */

package org.openziti.mobile.net

import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Test
import org.openziti.net.dns.DNSResolver
import org.pcap4j.packet.DnsPacket
import org.pcap4j.packet.IpSelector
import org.pcap4j.packet.UdpPacket
import java.io.Writer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.*
import java.util.function.Consumer

class DNSManagerTest {

    val msg = Base64.getDecoder().decode("RQAAQ817QABAERkvqf4AAan+AAKcXwA1AC+f+5SfAQAAAQAAAAAAAAdjYXRob3N0Cm5ldGZvdW5kcnkCaW8AAAEAAQ==")

    private val expectedHost = "cathost.netfoundry.io"

    val dnsResolver = object : DNSResolver {
        override fun resolve(hostname: String): InetAddress? = when (hostname) {
            expectedHost -> InetAddress.getByAddress(byteArrayOf(0x10, 0x11, 0x12, 0x33))
            else -> null
        }

        override fun subscribe(sub: (DNSResolver.DNSEvent) -> Unit) {
            TODO("Not yet implemented")
        }

        override fun subscribe(sub: Consumer<DNSResolver.DNSEvent>) {
            TODO("Not yet implemented")
        }

        override fun dump(writer: Writer) {
            TODO("Not yet implemented")
        }
    }

    @Test
    fun resolve() {
        val pack = IpSelector.newPacket(msg, 0, msg.size)
        val udpM = pack.payload as UdpPacket


        val dns = DNS(dnsResolver)

        val dnsResp = dns.resolve(udpM.payload as DnsPacket)
        assertEquals(1, dnsResp.header.qdCountAsInt)
        assertEquals(1, dnsResp.header.anCountAsInt)
    }

    @Test
    @Ignore("this runs DNS server for testing DNS packet formatting. " +
            "Test it: $ dig -p 15353 @localhost cathost.netfoundry.io")
    fun dnsServer() {
        val s = DatagramSocket(15353)

        while (true) {
            val buf = ByteArray(1024)
            val p = DatagramPacket(buf, 0, buf.size)

            s.receive(p)

            val dns = DNS(dnsResolver)

            val reqBuf = p.data
            val dnsReq = DnsPacket.newPacket(reqBuf, 0, reqBuf.size)
            val resp = dns.resolve(dnsReq)

            val respBuf = resp.rawData
            val respPacket = DatagramPacket(respBuf, 0, respBuf.size, p.address, p.port)
            s.send(respPacket)
        }
    }
}