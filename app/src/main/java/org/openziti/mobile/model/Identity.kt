/*
 * Copyright (c) 2024 NetFoundry. All rights reserved.
 */

package org.openziti.mobile.model

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import org.openziti.tunnel.ConfigEvent
import org.openziti.tunnel.ContextEvent
import org.openziti.tunnel.Event
import org.openziti.tunnel.ExtJWTEvent
import org.openziti.tunnel.JwtSigner
import org.openziti.tunnel.Service
import org.openziti.tunnel.ServiceEvent
import org.openziti.tunnel.ZitiConfig
import java.net.URI

class Identity(
    val id: String,
    var cfg: ZitiConfig,
    private val tunnel: TunnelModel,
    enable: Boolean = true
): ViewModel() {

    sealed class AuthState(val label: String)
    data object AuthNone: AuthState("Initial")
    data object Authenticated: AuthState("Authenticated")
    data class AuthJWT(val providers: List<JwtSigner>): AuthState("Login with JWT")

    val zitiID: String =
        with(URI(id)){
            userInfo ?: path?.removePrefix("/")
        } ?: id


    private val authState = MutableStateFlow<AuthState>(AuthNone)
    fun authState() = authState.asLiveData()

    fun name(): LiveData<String> = name
    internal val name = MutableLiveData(id)

    fun status(): LiveData<String> = status
    internal val status = MutableLiveData("Loading")

    private val controllers = MutableLiveData(cfg.controllers?.toList() ?: listOf(cfg.controller))
    fun controllers() = controllers

    private val enabled = MutableLiveData(enable)
    fun enabled(): LiveData<Boolean> = enabled

    private val serviceMap = mutableMapOf<String, Service>()
    private val services = MutableLiveData<List<Service>>()
    fun services(): LiveData<List<Service>> = services

    fun refresh() {
        tunnel.refreshIdentity(id).handleAsync { _, ex ->
            ex?.let {
                Log.w(TunnelModel.TAG, "failed refresh", it)
            }
        }
    }

    fun useJWTSigner(signer: String) =
        tunnel.useJWTSigner(id, signer)

    fun setEnabled(on: Boolean) {
        if (on)
            Log.i(TunnelModel.TAG, "enabling[${name.value}]")
        else
            Log.i(TunnelModel.TAG, "disabling[${name.value}]")

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

    fun processEvent(ev: Event):Unit = when (ev) {
        is ContextEvent -> {
            name.postValue(ev.name)
            if (ev.status == "OK") {
                status.postValue("Active")
                authState.value = Authenticated
            } else {
                status.postValue(ev.status)
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

        else -> {
            Log.w(TAG, "unhandled event[$ev]")
            Unit
        }
    }
    companion object {
        const val TAG = "model"
    }
}