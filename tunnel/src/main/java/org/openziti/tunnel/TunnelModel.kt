/*
 * Copyright (c) 2024 NetFoundry. All rights reserved.
 */

package org.openziti.tunnel

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import java.net.URI
import java.security.KeyStore.PrivateKeyEntry
import java.security.cert.X509Certificate
import java.util.concurrent.CompletableFuture

class TunnelModel(
    val cmd: Function1<TunnelCommand, CompletableFuture<TunnelResult>>,
    val event: ReceiveChannel<String>) {


    val scope = CoroutineScope(Dispatchers.IO)
    init {
        scope.launch {
            while (true) {
                val s = event.receive()
                Log.i("model", "received event[$s]")
            }
        }

        val aliases = Keychain.store.aliases().toList()

        val configs = aliases.filter { it.startsWith("ziti://") }
            .map { Pair(it, Keychain.store.getEntry(it, null)) }
            .filter { it.second is PrivateKeyEntry }
            .map {
                val pk = it.second
                val uri = URI(it.first)
                val ctrl = "https://${uri.host}:${uri.port}"
                val id = uri.path.removePrefix("/")

                val idCerts = Keychain.store.getCertificateChain(it.first)
                val pem = idCerts.map { it as X509Certificate }
                    .joinToString(transform = X509Certificate::toPEM, separator = "")
                val caCerts = aliases.filter { it.startsWith("ziti:$id/") }
                    .map {Keychain.store.getCertificate(it) as X509Certificate}
                    .joinToString(transform = X509Certificate::toPEM, separator = "")
                LoadIdentity(identifier = it.first,
                    config = ZitiConfig(
                        controller = ctrl,
                        controllers = listOf(ctrl),
                        id = ZitiID(cert = pem, key = "keychain:${it.first}", ca = caCerts)
                    )
                )
            }
        for (c in configs) {
            cmd.invoke(c).handleAsync { res: TunnelResult?, ex: Throwable? ->
                ex?.let {
                    Log.w("model", "failed to execute", it)
                }
                res?.let {
                    Log.i("model", "load result[${c.identifier}]: $res")
                }
            }
        }
    }

    companion object {
        lateinit var Model: TunnelModel
        fun init(events: ReceiveChannel<String>, cmd: Function1<TunnelCommand, CompletableFuture<TunnelResult>>) {
            Model = TunnelModel(cmd, events)
        }
    }
}