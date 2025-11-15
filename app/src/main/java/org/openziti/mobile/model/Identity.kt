/*
 * Copyright (c) 2025 NetFoundry. All rights reserved.
 */

package org.openziti.mobile.model

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.openziti.tunnel.ConfigEvent
import org.openziti.tunnel.ContextEvent
import org.openziti.tunnel.Event
import org.openziti.tunnel.ExtAuthResult
import org.openziti.tunnel.ExtJWTEvent
import org.openziti.tunnel.JwtSigner
import org.openziti.tunnel.RouterEvent
import org.openziti.tunnel.RouterStatus
import org.openziti.tunnel.Service
import org.openziti.tunnel.ServiceEvent
import org.openziti.tunnel.ZitiConfig
import java.net.URI
import java.time.Clock.*
import java.util.concurrent.CompletableFuture
import timber.log.Timber as Log

class Identity(
    val id: String,
    var cfg: ZitiConfig,
    private val tunnel: TunnelModel,
    enable: Boolean = true
): ViewModel() {
    val Context.prefs: DataStore<Preferences> by preferencesDataStore(id)

    sealed class AuthState(val label: String)
    data object AuthNone: AuthState("Initial")
    data object Authenticated: AuthState("Authenticated")
    data class AuthJWT(val providers: List<JwtSigner>): AuthState("Login with JWT")

    val zitiID: String =
        with(URI(id)){
            userInfo ?: path?.removePrefix("/")
        } ?: id

    internal var lastRefresh = systemUTC().instant()
    private val authState = MutableStateFlow<AuthState>(AuthNone)
    fun authState() = authState.asLiveData()

    fun name(): LiveData<String> = name
    internal val name = MutableLiveData(id)

    fun status(): LiveData<String> = status
    internal val status = MutableLiveData("Loading")

    private val controllers = MutableLiveData(cfg.controllers.toList())
    fun controllers() = controllers

    private val routers = mutableMapOf<String, RouterEvent>()
    private val rtData = MutableLiveData<Map<String, RouterEvent>>(routers)
    fun routers(): LiveData<Map<String, RouterEvent>> = rtData

    private val enabled = MutableLiveData(enable)
    fun enabled(): LiveData<Boolean> = enabled

    private val serviceMap = mutableMapOf<String, Service>()
    private val services = MutableLiveData<List<Service>>()
    fun services(): LiveData<List<Service>> = services

    private val nameObserver = Observer { newName: String? ->
        if (newName.isNullOrBlank())
            return@Observer

        runBlocking {
            with(tunnel.context()) {
                val curr = prefs.data.first()[nameKey]
                if (curr != newName) {
                    prefs.edit {
                        it[nameKey] = newName
                    }
                }
            }
        }
    }

    internal fun start() {
        Log.i("starting id[$id]")
        runCatching {
            runBlocking(Dispatchers.Main) {
                tunnel.context().prefs.data.first().asMap()[nameKey]?.toString()?.let {
                    name.postValue(it)
                }
                name.observeForever(nameObserver)
            }
        }.onFailure {
            Log.w(it, "failed to read name from prefs")
        }
    }

    override fun onCleared() {
        Log.i("onCleared id[$id/${name().value}]")
        name.removeObserver(nameObserver)
        super.onCleared()
    }

    fun refresh() {
        if (status.value == "Active") {
            Log.d("identity[$id/${name().value}] starting refresh ")
            tunnel.refreshIdentity(id).thenAcceptAsync {
                Log.d("identity[$id/${name().value}] refresh completed")
                lastRefresh = systemUTC().instant()
            }.exceptionallyAsync { ex ->
                Log.w(ex, "identity[$id/$name] refresh failed")
                null
            }
        }
    }

    fun useJWTSigner(signer: String?): CompletableFuture<ExtAuthResult> =
        tunnel.useJWTSigner(id, signer)

    fun setEnabled(on: Boolean) {
        Log.i("""${if (on) "en" else "dis"}abling[$id/${name.value}]""")

        tunnel.enableIdentity(id, on).thenAccept {
            enabled.postValue(on)
            if (on) {
                status.postValue("Enabled")
            } else {
                authState.value = AuthNone
                status.postValue("Disabled")
            }
        }
    }

    fun delete() {
        setEnabled(false)
        tunnel.context().preferencesDataStoreFile(id).delete()
        tunnel.deleteIdentity(id, cfg.id.key?.removePrefix("keychain:"))
    }

    private fun updateConfig(config: ZitiConfig) {
        cfg = config
        controllers.postValue(cfg.controllers.toList())
    }

    private fun processServiceUpdate(ev: ServiceEvent) {
        for (s in ev.removedServices) {
            serviceMap.remove(s.id)
        }

        for (s in ev.addedServices) {
            serviceMap[s.id] = s
        }

        services.postValue(serviceMap.values.toList())
    }

    fun processEvent(ev: Event) {
        Log.d("identity[$id/${name.value}] received event[$ev]")
        when (ev) {
            is ContextEvent -> {
                ev.name?.let {
                    name.postValue(it)
                }
                if (ev.status == "OK") {
                    status.postValue("Active")
                    authState.value = Authenticated
                } else {
                    status.postValue(ev.status)
                }

                if (ev.status == "ziti context is disabled") {
                    authState.value = AuthNone
                    routers.clear()
                    rtData.postValue(routers)
                }
            }

            is ServiceEvent -> processServiceUpdate(ev)

            is ConfigEvent -> {
                updateConfig(ev.config)
                val json = Json.encodeToString(ZitiConfig.serializer(), cfg)
                tunnel.identitiesDir.resolve(id).outputStream().use { out ->
                    out.write(json.toByteArray())
                }
            }

            is ExtJWTEvent -> {
                authState.value = AuthJWT(ev.providers)
            }

            is RouterEvent -> {
                if (ev.status == RouterStatus.REMOVED) {
                    routers.remove(ev.name)
                } else {
                    routers[ev.name] = ev
                }
                rtData.postValue(routers)
            }
        }
    }

    companion object {
        private val nameKey = stringPreferencesKey("name")
    }
}