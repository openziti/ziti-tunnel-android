/*
 * Copyright (c) 2024 NetFoundry. All rights reserved.
 */

package org.openziti.mobile

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import org.openziti.tunnel.APIEvent
import org.openziti.tunnel.ContextEvent
import org.openziti.tunnel.Enroll
import org.openziti.tunnel.Event
import org.openziti.tunnel.Keychain
import org.openziti.tunnel.LoadIdentity
import org.openziti.tunnel.Service
import org.openziti.tunnel.ServiceEvent
import org.openziti.tunnel.SetUpstreamDNS
import org.openziti.tunnel.Tunnel
import org.openziti.tunnel.TunnelResult
import org.openziti.tunnel.ZitiConfig
import org.openziti.tunnel.ZitiID
import org.openziti.tunnel.toPEM
import java.net.URI
import java.security.KeyStore.PrivateKeyEntry
import java.security.cert.X509Certificate

class TunnelModel(
    val tunnel: Tunnel,
    val context: Context
): ViewModel() {
    val Context.prefs: DataStore<Preferences> by preferencesDataStore("tunnel")

    val NAMESERVER = stringPreferencesKey("nameserver")
    val zitiDNS = context.prefs.data.map {
        it[NAMESERVER] ?: "100.64.0.2"
    }
    val RANGE = stringPreferencesKey("range")
    val zitiRange = context.prefs.data.map {
        it[RANGE] ?: "100.64.0.0/10"
    }

    fun setDNS(server: String?, range: String?) = runBlocking {
        context.prefs.edit { settings ->
            settings[NAMESERVER] = server ?: defaultDNS
            settings[RANGE] = range ?: defaultRange
        }
    }

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
        runBlocking {
            val dns = zitiDNS.first()
            val range = zitiRange.first()
            Log.i("tunnel", "setting dns[$dns] and range[$range]")
            tunnel.setupDNS(dns, range)
            tunnel.start()
        }
        scope.launch {
            tunnel.events().collect(this@TunnelModel::processEvent)
        }

        val aliases = Keychain.store.aliases().toList()

        aliases.filter { it.startsWith("ziti://") }
            .map { Pair(it, Keychain.store.getEntry(it, null)) }
            .filter { it.second is PrivateKeyEntry }
            .map {
                val uri = URI(it.first)
                val ctrl = "https://${uri.host}:${uri.port}"
                val id = uri.userInfo ?: uri.path.removePrefix("/")

                val idCerts = Keychain.store.getCertificateChain(it.first)
                val pem = idCerts.map { it as X509Certificate }
                    .joinToString(transform = X509Certificate::toPEM, separator = "")
                val caCerts = aliases.filter { it.startsWith("ziti:$id/") }
                    .map { Keychain.store.getCertificate(it) as X509Certificate}
                    .joinToString(transform = X509Certificate::toPEM, separator = "")
                it.first to ZitiConfig(
                    controller = ctrl,
                    controllers = listOf(ctrl),
                    id = ZitiID(cert = pem, key = "keychain:${it.first}", ca = caCerts)
                )
            }.forEach {
                loadConfig(it.first, it.second)
            }
    }

    private fun loadConfig(ident: String, cfg: ZitiConfig) {
        Log.i("model", "loading identity[$ident]")
        val cmd = LoadIdentity(ident, cfg)
        tunnel.processCmd(cmd).handleAsync { res: TunnelResult?, ex: Throwable? ->
            ex?.let {
                Log.w("model", "failed to execute", it)
            }
            res?.let {
                if (it.success) {
                    identities[ident] = TunnelIdentity(ident)
                    identitiesData.postValue(identities.values.toList())
                }
                Log.i("model", "load result[$ident]: $it")
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

    fun setUpstreamDNS(server: String) {
        tunnel.processCmd(SetUpstreamDNS(server)).handleAsync { _, ex ->
            ex?.let { Log.i("model", "failed to set upstream DNS", it)
            }
        }
    }

    fun enroll(jwt: String) {
        val cmd = Enroll(
            jwt = jwt,
            useKeychain = true)
        tunnel.processCmd(cmd).handleAsync { res, ex ->
            Log.i("model", "$res")
            ex?.let {
                Toast.makeText(context, ex.message, Toast.LENGTH_LONG).show()
            }

            res?.let {
                if (!it.success) {
                    Toast.makeText(context, it.error, Toast.LENGTH_LONG).show()
                } else {
                    it.data?.let {
                        val cfg = Json.decodeFromJsonElement<ZitiConfig>(it)
                        val keyAlias = cfg.id.key.removePrefix("keychain:")
                        Keychain.updateKeyEntry(keyAlias, cfg.id.cert, cfg.id.ca)
                        Toast.makeText(context, "Enrolled Successfully!", Toast.LENGTH_LONG).show()
                        loadConfig(keyAlias, cfg)
                    }
                }
            }
        }
    }

    companion object {
        val defaultDNS = "100.64.0.2"
        val defaultRange = "100.64.0.0/10"
    }
}