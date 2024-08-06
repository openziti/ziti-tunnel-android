/*
 * Copyright (c) 2024 NetFoundry. All rights reserved.
 */

package org.openziti.mobile

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.openziti.tunnel.APIEvent
import org.openziti.tunnel.ContextEvent
import org.openziti.tunnel.Event
import org.openziti.tunnel.Keychain
import org.openziti.tunnel.LoadIdentity
import org.openziti.tunnel.Service
import org.openziti.tunnel.ServiceEvent
import org.openziti.tunnel.Tunnel
import org.openziti.tunnel.TunnelResult
import org.openziti.tunnel.ZitiConfig
import org.openziti.tunnel.ZitiID
import org.openziti.tunnel.toPEM
import java.net.URI
import java.security.KeyStore.PrivateKeyEntry
import java.security.cert.X509Certificate

class TunnelModel(
    val tunnel: Tunnel
): ViewModel() {
    val scope = CoroutineScope(Dispatchers.IO)

    data class NetworkStats(val up: Double, val down: Double)
    fun stats(): LiveData<NetworkStats> = stats
    internal val stats = MutableLiveData(NetworkStats(1.0,1.0))

    fun identities(): LiveData<List<TunnelIdentity>> = identitiesData
    private val identitiesData = MutableLiveData<List<TunnelIdentity>>()
    private val identities = mutableMapOf<String, TunnelIdentity>()

    class Factory(private val identity: TunnelIdentity): ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return identity as T
        }
    }

    class TunnelIdentity(val id: String): ViewModel() {

        fun name(): LiveData<String> = name
        internal val name = MutableLiveData(id)

        fun status(): LiveData<String> = status
        internal val status = MutableLiveData<String>("Loading")

        internal val controller = MutableLiveData<String>("<controller")
        fun controller() = controller

        private val enabled = MutableLiveData(true)
        fun enabled(): LiveData<Boolean> = enabled

        private val serviceMap = mutableMapOf<String, Service>()
        private val services = MutableLiveData<List<Service>>()
        fun services(): LiveData<List<Service>> = services

        fun setEnabled(enabled: Boolean) {
            // TODO
        }

        fun delete() {
            // TODO
        }

        internal fun processServiceUpdate(ev: ServiceEvent) {
            for (s in ev.removedServices) {
                serviceMap.remove(s.id)
            }

            for (s in ev.addedServices) {
                serviceMap[s.id] = s
            }

            services.postValue(serviceMap.values.toList())
        }
    }

    init {
        scope.launch {
            tunnel.events().collect(this@TunnelModel::processEvent)
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
                    .map { Keychain.store.getCertificate(it) as X509Certificate}
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
            tunnel.processCmd(c).handleAsync { res: TunnelResult?, ex: Throwable? ->
                ex?.let {
                    Log.w("model", "failed to execute", it)
                }
                res?.let {
                    if (it.success) {
                        identities[c.identifier] = TunnelIdentity(c.identifier)
                        identitiesData.postValue(identities.values.toList())
                    }
                    Log.i("model", "load result[${c.identifier}]: $it")
                }
            }
        }
    }

    private fun processEvent(ev: Event) {
        val tunnelIdentity = identities[ev.identifier]
        when(ev) {
            is ContextEvent -> {
                tunnelIdentity?.apply {
                    name.postValue(ev.name)
                    controller.postValue(ev.controller)
                    if (ev.status == "OK") {
                        tunnelIdentity.status.postValue("Active")
                    } else {
                        tunnelIdentity.status.postValue(ev.status)
                    }
                }
            }
            is ServiceEvent -> {
                tunnelIdentity?.processServiceUpdate(ev)
            }
            is APIEvent -> {

            }
            else -> {
                Log.i("model", "received event[$ev]")
            }
        }
    }
}