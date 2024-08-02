/*
 * Copyright (c) 2024 NetFoundry. All rights reserved.
 */

package org.openziti.tunnel

import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.KeyStore.PrivateKeyEntry
import java.security.Signature

class Keychain(val store: KeyStore) {

    // id matching tlsuv key enum
    enum class KeyType(val id: Int) {
        EC(1),
        RSA(2),
    }

    fun loadKey(name: String): PrivateKeyEntry? {
        val k = store.getEntry(name, null)
        return if (k is PrivateKeyEntry) k else null
    }

    fun keyType(ke: PrivateKeyEntry) = runCatching {
        KeyType.valueOf(ke.privateKey.algorithm).id
    }.getOrDefault(0)

    fun pubKey(ke: PrivateKeyEntry): ByteArray {
        val fmt = ke.certificate.publicKey.format
        val b = ke.certificate.publicKey.encoded
        return b
    }

    fun sign(ke: PrivateKeyEntry, data: ByteBuffer): ByteArray? {
        val priv = ke.privateKey

        val sig = when(priv.algorithm) {
            "EC" -> Signature.getInstance("NONEwithECDSA")
            "RSA" -> Signature.getInstance("NONEwithRSA")
            else -> return null
        }

        sig.initSign(priv)
        sig.update(data)
        return sig.sign()
    }

    companion object {
        @JvmStatic
        external fun registerKeychain(chain: Keychain)
        val store = KeyStore.getInstance("AndroidKeystore")!!
        init {
            store.load(null)
            registerKeychain(Keychain(store))
        }

        @JvmStatic
        external fun testNativeKey(name: String): Boolean
    }
}