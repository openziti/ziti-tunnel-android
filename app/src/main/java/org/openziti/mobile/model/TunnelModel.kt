/*
 * Copyright (c) 2024 NetFoundry. All rights reserved.
 */

package org.openziti.mobile.model

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import org.openziti.tunnel.Dump
import org.openziti.tunnel.Enroll
import org.openziti.tunnel.Event
import org.openziti.tunnel.ExtAuthResult
import org.openziti.tunnel.ExternalAuth
import org.openziti.tunnel.Keychain
import org.openziti.tunnel.LoadIdentity
import org.openziti.tunnel.OnOffCommand
import org.openziti.tunnel.RefreshIdentity
import org.openziti.tunnel.RemoveIdentity
import org.openziti.tunnel.SetUpstreamDNS
import org.openziti.tunnel.Tunnel
import org.openziti.tunnel.Upstream
import org.openziti.tunnel.ZitiConfig
import org.openziti.tunnel.ZitiID
import org.openziti.tunnel.toPEM
import java.net.URI
import java.security.KeyStore.PrivateKeyEntry
import java.security.cert.X509Certificate
import java.util.concurrent.CompletableFuture

class TunnelModel(
    val tunnel: Tunnel,
    val context: () -> Context
): ViewModel() {
    val Context.prefs: DataStore<Preferences> by preferencesDataStore("tunnel")
    val identitiesDir = context().getDir("identities", Context.MODE_PRIVATE)

    val NAMESERVER = stringPreferencesKey("nameserver")
    val zitiDNS = context().prefs.data.map {
        it[NAMESERVER] ?: "100.64.0.2"
    }
    val RANGE = stringPreferencesKey("range")
    val zitiRange = context().prefs.data.map {
        it[RANGE] ?: "100.64.0.0/10"
    }

    fun setDNS(server: String?, range: String?) = runBlocking {
        context().prefs.edit { settings ->
            settings[NAMESERVER] = server ?: DEFAULT_DNS
            settings[RANGE] = range ?: DEFAULT_RANGE
        }
    }

    data class NetworkStats(val up: Double, val down: Double)
    fun stats() = flow {
        while (true) {
            emit(NetworkStats(tunnel.getUpRate(), tunnel.getDownRate()))
            delay(1_000)
        }
    }.asLiveData()

    fun identities(): LiveData<List<Identity>> = identitiesData
    private val identitiesData = MutableLiveData<List<Identity>>()
    private val identities = mutableMapOf<String, Identity>()

    fun identity(id: String): Identity? = identities[id]

    init {
        runBlocking {
            val dns = zitiDNS.first()
            val range = zitiRange.first()
            Log.i("tunnel", "setting dns[$dns] and range[$range]")
            tunnel.setupDNS(dns, range)
            tunnel.start()
        }
        viewModelScope.launch {
            tunnel.events().collect { ev ->
                runCatching {
                    processEvent(ev)
                }.onFailure {
                    Log.e(TAG, "failed to process event[$ev]", it)
                }
            }
        }

        val configs = mutableMapOf<String, ZitiConfig>()

        val idFiles = identitiesDir.listFiles() ?: emptyArray()
        idFiles.forEach {
            Log.i(TAG, "loading identity from file[$it]")
            val cfg = Json.decodeFromString<ZitiConfig>(it.readText())
            configs[cfg.identifier] = cfg
        }

        val loadedKeys = configs.mapNotNull { it.value.id.key?.removePrefix("keychain:") }

        val aliases = Keychain.store.aliases().toList().filter { !loadedKeys.contains(it) }

        for (alias in aliases) {
            loadConfigFromKeyStore(alias)?.let { cfg ->
                Log.i(TAG, "migrating identity from keychain[$alias]")
                val uri = URI(alias)
                val id = uri.userInfo ?: uri.path.removePrefix("/")
                val json = Json.encodeToString(ZitiConfig.serializer(), cfg)
                identitiesDir.resolve(cfg.identifier).outputStream().use {
                    it.write(json.toByteArray())
                }
                configs[id] = cfg
            }
        }

        for (it in configs) {
            loadIdentity(it.key, it.value)
        }
    }

    private fun loadConfigFromKeyStore(alias: String): ZitiConfig? {
        if (!Keychain.store.containsAlias(alias)) return null
        if (!alias.startsWith("ziti://")) return null

        val entry = Keychain.store.getEntry(alias, null)
        if (entry !is PrivateKeyEntry) return null

        val uri = URI(alias)
        val ctrl = "https://${uri.host}:${uri.port}"
        val id = uri.userInfo ?: uri.path.removePrefix("/")

        val idCerts = Keychain.store.getCertificateChain(alias)
        val pem = idCerts.map { it as X509Certificate }
            .joinToString(transform = X509Certificate::toPEM, separator = "")
        val caCerts = Keychain.store.aliases().toList().filter { it.startsWith("ziti:$id/") }
            .map { Keychain.store.getCertificate(it) as X509Certificate}
            .joinToString(transform = X509Certificate::toPEM, separator = "")

        return ZitiConfig(
            controller = ctrl,
            controllers = listOf(ctrl),
            id = ZitiID(cert = pem, key = "keychain:${alias}", ca = caCerts)
        )
    }

    private fun disabledKey(id: String) = booleanPreferencesKey("$id.disabled")

    private fun loadIdentity(id: String, cfg: ZitiConfig) {
        val disabled = runBlocking {
            context().prefs.data.map {
                it[disabledKey(id)] ?: false
            }.first()
        }
        Log.i(TAG, "loading identity[$id] disabled[$disabled]")
        val idModel = Identity(id, cfg, this, !disabled)
        identities[id] = idModel
        val cmd = LoadIdentity(id, cfg, disabled)
        tunnel.processCmd(cmd).handleAsync { json: JsonElement? , ex: Throwable? ->
            idModel.start()
            if (ex != null) {
                Log.w(TAG, "failed to execute", ex)
            } else  {
                identitiesData.postValue(identities.values.toList())
                Log.i(TAG, "load result[$id]: $json")
            }
        }
    }

    private fun processEvent(ev: Event) {
        Log.d(TAG, "received event[$ev]")

        identities[ev.identifier]?.processEvent(ev)
            ?: Log.w(TAG, "no identity for event[$ev]")
    }

    fun setUpstreamDNS(servers: List<String>): CompletableFuture<Unit> {
        val cmd = SetUpstreamDNS(servers.map { Upstream(it) })
        return tunnel.processCmd(cmd).thenApply {  }
    }

    fun enroll(jwtOrUrl: String): CompletableFuture<ZitiConfig?>  {
        val cmd = if (jwtOrUrl.startsWith("https://"))
            Enroll(url = jwtOrUrl, useKeychain = false)
        else
            Enroll(jwt = jwtOrUrl, useKeychain = true)

        val future = tunnel.processCmd(cmd).thenApply {
            Json.decodeFromJsonElement<ZitiConfig>(it!!)
        }

        future.thenApply { cfg ->
            val cfgJson = Json.encodeToString(ZitiConfig.serializer(), cfg)
            identitiesDir.resolve(cfg.identifier).outputStream().use {
                it.write(cfgJson.toByteArray())
            }

            loadIdentity(cfg.identifier, cfg)
        }.exceptionally {
            Log.e(TAG, "enrollment failed", it)
        }
        return future
    }

    fun dumpIdentity(id: String): CompletableFuture<String> =
        tunnel.processCmd(Dump(id)).thenApply { json ->
            if (json is JsonObject && json[id] is JsonPrimitive) {
                json[id]?.jsonPrimitive?.content
            } else {
                json?.toString() ?: "no data"
            }
        }

    internal fun refreshIdentity(id: String) =
        tunnel.processCmd(RefreshIdentity(id)).thenAccept{}

    internal fun useJWTSigner(id: String, signer: String?) =
        tunnel.processCmd(ExternalAuth(id, signer)).thenApply {
            Json.decodeFromJsonElement<ExtAuthResult>(it!!)
        }

    internal fun enableIdentity(id: String, on: Boolean): CompletableFuture<Unit> {
        if (identities.contains(id)) {
            val disabledKey = disabledKey(id)
            runBlocking {
                context().prefs.edit {
                    it[disabledKey] = !on
                }
            }
            return tunnel.processCmd(OnOffCommand(id, on)).thenApply {}
        }

        return CompletableFuture.completedFuture(Unit)
    }

    internal fun deleteIdentity(identifier: String, key: String?) {
        tunnel.processCmd(RemoveIdentity(identifier)).thenApply {
            identities.remove(identifier)
            identitiesData.postValue(identities.values.toList())

            val uri = URI(identifier)
            val id = uri.userInfo ?: uri.path.removePrefix("/")

            key?.let {
                runCatching { Keychain.store.deleteEntry(key) }
                    .onFailure { Log.w(TAG, "failed to remove entry", it) }
            }

            runCatching {
                identitiesDir.resolve(id).delete()
            }.onFailure {
                Log.w(TAG, "failed to remove config", it)
            }

            val caCerts = Keychain.store.aliases().toList().filter { it.startsWith("ziti:$id/") }
            caCerts.forEach {
                Keychain.store.runCatching {
                    deleteEntry(it)
                }
            }

            runBlocking {
                context().prefs.edit {
                    val prefKey = disabledKey(identifier)
                    if (it.contains(prefKey))
                        it.remove(prefKey)
                }
            }
        }
    }

    companion object {
        const val TAG = "model"
        const val DEFAULT_DNS = "100.64.0.2"
        const val DEFAULT_RANGE = "100.64.0.0/10"
    }
}