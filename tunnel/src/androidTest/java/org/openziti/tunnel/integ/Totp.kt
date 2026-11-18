/*
 * Copyright (c) 2026 NetFoundry. All rights reserved.
 */

package org.openziti.tunnel.integ

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The authenticator side of TOTP (RFC 6238: HMAC-SHA1, 30 s steps, 6 digits, which is what the controller issues). */
object Totp {
    private const val STEP_SECONDS = 30L
    private const val DIGITS = 6
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** The code for the base32 [secret] at [atMillis]. */
    fun code(secret: String, atMillis: Long = System.currentTimeMillis()): String {
        val counter = ByteBuffer.allocate(8).putLong(atMillis / 1000 / STEP_SECONDS).array()
        val mac = Mac.getInstance("HmacSHA1").run {
            init(SecretKeySpec(base32(secret), "HmacSHA1"))
            doFinal(counter)
        }
        val offset = mac.last().toInt() and 0x0f
        val binary = ((mac[offset].toInt() and 0x7f) shl 24) or
            ((mac[offset + 1].toInt() and 0xff) shl 16) or
            ((mac[offset + 2].toInt() and 0xff) shl 8) or
            (mac[offset + 3].toInt() and 0xff)
        var modulus = 1
        repeat(DIGITS) { modulus *= 10 }
        return (binary % modulus).toString().padStart(DIGITS, '0')
    }

    private fun base32(input: String): ByteArray {
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in input.trimEnd('=').uppercase()) {
            val value = ALPHABET.indexOf(c)
            require(value >= 0) { "not a base32 character: '$c'" }
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        return out.toByteArray()
    }
}
