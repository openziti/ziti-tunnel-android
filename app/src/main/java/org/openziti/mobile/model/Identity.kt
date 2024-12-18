/*
 * Copyright (c) 2024 NetFoundry. All rights reserved.
 */

package org.openziti.mobile.model

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import kotlinx.serialization.json.Json
import org.openziti.tunnel.APIEvent
import org.openziti.tunnel.ContextEvent
import org.openziti.tunnel.Event
import org.openziti.tunnel.ExtJWTEvent
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

    val zitiID: String =
        with(URI(id)){
            userInfo ?: path?.removePrefix("/")
        } ?: id


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

    fun setEnabled(on: Boolean) {
        if (on)
            Log.i(TunnelModel.TAG, "enabling[${name.value}]")
        else
            Log.i(TunnelModel.TAG, "disabling[${name.value}]")

        tunnel.enableIdentity(id, on).thenAccept {
            enabled.postValue(on)
            if (on)
                status.postValue("Enabled")
            else
                status.postValue("Disabled")
        }
    }

    fun delete() {
        setEnabled(false)
        tunnel.deleteIdentity(id, cfg.id.key?.removePrefix("keychain:"))
    }

    private fun updateConfig(config: ZitiConfig) {
        cfg = config
        controllers.postValue(cfg.controllers?.toList() ?: listOf(cfg.controller))
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
        Log.d(TAG, "received event[$ev]")
        when(ev) {
            is ContextEvent -> {
                name.postValue(ev.name)
                if (ev.status == "OK") {
                    status.postValue("Active")
                } else {
                    status.postValue(ev.status)
                }
            }
            is ServiceEvent -> processServiceUpdate(ev)
            is APIEvent -> {
                updateConfig(ev.config)
                val json = Json.encodeToString(ZitiConfig.serializer(), cfg)
                tunnel.identitiesDir.resolve(id).outputStream().use { out ->
                    out.write(json.toByteArray())
                }
            }
            is ExtJWTEvent -> {
            }
            else -> {
                Log.w(TAG, "unhandled event[$ev]")
            }
        }
    }
    companion object {
        const val TAG = "model"
    }
}