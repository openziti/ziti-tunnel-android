/*
 * Copyright (c) 2024 NetFoundry. All rights reserved.
 */

package org.openziti.tunnel

import android.util.Base64
import java.security.cert.X509Certificate

fun X509Certificate.toPEM(): String {
    val body = Base64.encode(this.encoded, Base64.DEFAULT).toString(Charsets.UTF_8)
    return """-----BEGIN CERTIFICATE-----
            |$body-----END CERTIFICATE-----
            |""".trimMargin()
}