/*
 * Copyright (c) 2024 NetFoundry. All rights reserved.
 */

package org.openziti.tunnel

import android.util.Base64
import java.net.Inet4Address
import java.net.InetAddress
import java.security.cert.X509Certificate
import kotlin.experimental.and

fun X509Certificate.toPEM(): String {
    val body = Base64.encode(this.encoded, Base64.DEFAULT).toString(Charsets.UTF_8)
    return """-----BEGIN CERTIFICATE-----
            |$body-----END CERTIFICATE-----
            |""".trimMargin()
}


data class Route(val address: InetAddress, val bits: Int) {
    fun includes(other: Route): Boolean {
        if (other.address.address.size != address.address.size) return false
        if (other.bits < bits) return false

        var b = bits
        var idx = 0
        while (b >= 8) {
            if (address.address[idx] != other.address.address[idx]) return false
            b -= 8
            idx++
        }
        if (b == 0) return true

        val mask = masks[b]!!.toByte()
        return address.address[idx].and(mask) == other.address.address[idx].and(mask)
    }
    companion object {
        val masks = mapOf(
            1 to 0b10000000,
            2 to 0b11000000,
            3 to 0b11100000,
            4 to 0b11110000,
            5 to 0b11111000,
            6 to 0b11111100,
            7 to 0b11111110,
            8 to 0b11111111,
        )
    }
}


fun String.toRoute(): Route {
    val subs = this.split('/', limit = 2)
    val addr = InetAddress.getByName(subs[0])
    val bits = if (subs.size > 1) subs[1].toInt()
    else if (addr is Inet4Address) 32
    else 128

    return Route(addr, bits)
}