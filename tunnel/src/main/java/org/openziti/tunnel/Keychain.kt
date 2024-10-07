/*
 * Copyright (c) 2024 NetFoundry. All rights reserved.
 */

package org.openziti.tunnel

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.net.URI
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.KeyStore.PrivateKeyEntry
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.RSAKeyGenParameterSpec

class Keychain(val store: KeyStore) {

    // id matching tlsuv key enum
    enum class KeyType(val id: Int) {
        EC(1),
        RSA(2),
    }

    fun genKey(name: String, type: String): PrivateKeyEntry? = runCatching {
        val generator = KeyPairGenerator.getInstance(type, store.provider)

        val spec = when (type) {
            "EC" ->  ECGenParameterSpec("prime256v1")
            "RSA" -> RSAKeyGenParameterSpec(4096, RSAKeyGenParameterSpec.F4)
            else -> null
        }

        val params = KeyGenParameterSpec.Builder(name, KeyProperties.PURPOSE_SIGN).apply {
            setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
            spec?.let { setAlgorithmParameterSpec(it) }
        }

        generator.initialize(params.build())
        val pair = generator.genKeyPair()

        val entry = store.getEntry(name, null)
        entry as PrivateKeyEntry?
    }.getOrNull()

    fun loadKey(name: String): PrivateKeyEntry? {
        val k = store.getEntry(name, null)
        return if (k is PrivateKeyEntry) k else null
    }

    fun deleteKey(name: String) {
        val k = store.getEntry(name, null)
        if (k !is PrivateKeyEntry) {
            Log.w("keystore", "entry[$name] is not a PrivateKeyEntry")
            return
        }
        store.deleteEntry(name)
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

        fun updateKeyEntry(alias: String, certs: String, ca: String) {
            val entry = store.getEntry(alias, null)
            require(entry is PrivateKeyEntry)

            val uri = URI(alias)
            val id = uri.userInfo ?: uri.path.removePrefix("/")

            val certificates = certs.toCerts()
            val caBundle = ca.toCerts()

            store.setKeyEntry(alias, entry.privateKey, null, certificates)

            for (c in caBundle) {
                store.setCertificateEntry("ziti:$id/${c.subjectDN}", c)
            }
        }
    }
}