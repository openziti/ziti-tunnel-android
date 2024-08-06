/*
 * Copyright (c) 2024 NetFoundry. All rights reserved.
 */

package org.openziti.tunnel

import android.util.Base64
import java.net.Inet4Address
import java.net.InetAddress
import java.security.cert.X509Certificate

fun X509Certificate.toPEM(): String {
    val body = Base64.encode(this.encoded, Base64.DEFAULT).toString(Charsets.UTF_8)
    return """-----BEGIN CERTIFICATE-----
            |$body-----END CERTIFICATE-----
            |""".trimMargin()
}

data class Route(val address: InetAddress, val bits: Int)
fun String.toRoute(): Route {
    val subs = this.split('/', limit = 2)
    val addr = InetAddress.getByName(subs[0])
    val bits = if (subs.size > 1) subs[1].toInt()
    else if (addr is Inet4Address) 32
    else 128

    return Route(addr, bits)
}