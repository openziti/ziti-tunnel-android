/*
 * Copyright (c) 2024 NetFoundry. All rights reserved.
 */

package org.openziti.tunnel

import android.app.Application
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.CompletableFuture

class Tunnel(app: Application, ): Runnable {
    val TAG = Tunnel::class.simpleName

    val cmdIPC: ParcelFileDescriptor
    val eventIPC: ParcelFileDescriptor
    val events = Channel<String>(capacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val routes = mutableMapOf<String, Int>()
    private val routeData = MutableStateFlow(emptySet<String>())

    init {
        val pm = app.packageManager

        val ver = pm.getPackageInfo(app.packageName, 0).versionName
        initNative(app.packageName, ver)

        val eventPipe = ParcelFileDescriptor.createPipe()
        eventIPC = eventPipe[0]
        val cmdPipes = ParcelFileDescriptor.createPipe()
        cmdIPC = cmdPipes[0]
    }

    fun onEvent(json: String) {
        Log.i(TAG, "event: $json")
        events.trySend(json).onFailure {
            Log.w(TAG, "failed to queue event")
        }
    }

    fun onResponse(json: String, f: CompletableFuture<TunnelResult>) {
        Log.i(TAG, "resp = $json")
        try {
            val resp = Json.decodeFromString<TunnelResult>(json)
            f.complete(resp)
        } catch (ex: Throwable) {
            Log.w(TAG, "failed to parse result[$json]", ex)
            f.completeExceptionally(ex)
        }
    }

    fun processCmd(cmd: TunnelCommand) = CompletableFuture<TunnelResult>().apply {
        executeCommand(cmd.cmd.name, Json.encodeToString(cmd), this)
    }

    fun start() {
        val t = Thread(this, "native-tunnel")
        t.start()
    }

    fun events() = events.consumeAsFlow().map {
        try {
            EventsJson.decodeFromString<Event>(it)
        } catch (ex: Exception) {
            Log.w("model", ex)
            null
        }
    }.filterNotNull()

    fun routes(): StateFlow<Set<String>> = routeData
    fun addRoute(rt: String) {
        routes.compute(rt){ _, count -> (count ?: 0) + 1 }
    }
    fun delRoute(rt: String) {
        routes.compute(rt){ _, count ->
            if (count == null) null
            else if(count - 1 == 0) null
            else count - 1
        }
    }
    fun commitRoutes() {
        routeData.tryEmit(routes.keys)
    }

    companion object {
        // Used to load the 'tunnel' library on application startup.
        init {
            System.loadLibrary("tunnel")
        }
    }


    external fun initNative(app: String, version: String)
    external fun setupIPC(cmdFD: Int, eventDF: Int)
    external fun tlsuvVersion(): String
    external fun zitiSdkVersion(): String
    external fun zitiTunnelVersion(): String

    external fun executeCommand(cmd: String, data: String, ctx: Any)

    external override fun run()

}