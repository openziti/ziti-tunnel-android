/*
 * Copyright (c) 2024 NetFoundry. All rights reserved.
 */

package org.openziti.tunnel

import android.app.Application
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

class Tunnel(app: Application, ): Runnable {
    val TAG = Tunnel::class.simpleName

    val cmdIPC: ParcelFileDescriptor
    val eventIPC: ParcelFileDescriptor
    val events = Channel<String>(capacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val routes = mutableMapOf<Route, Int>()
    private val routeData = MutableStateFlow(emptySequence<Route>())
    private var defaultRoute = "100.64.0.0/10".toRoute()

    init {
        val pm = app.packageManager

        val ver = pm.getPackageInfo(app.packageName, 0).versionName ?: "unknown"
        initNative(app.packageName, ver)

        val eventPipe = ParcelFileDescriptor.createPipe()
        eventIPC = eventPipe[0]
        val cmdPipes = ParcelFileDescriptor.createPipe()
        cmdIPC = cmdPipes[0]
    }

    fun onEvent(json: String) {
        Log.i(TAG, "event: $json")
        events.trySend(json).onFailure {
            Log.w(TAG, "failed to queue event", it)
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

    inline fun <reified C: TunnelCommand> processCmd(cmd: C): CompletableFuture<JsonElement?> =
        CompletableFuture<TunnelResult>().apply {
            val data = cmd.toJson()
            Log.d(TAG, "cmd[${cmd.id}] = ${cmd.cmd.name}:$data")
            executeCommand(cmd.cmd.name, data, this)
        }.thenApply {
            Log.d(TAG, "result[${cmd.id}] = ${cmd.cmd.name}:$it")
            if (!it.success) throw Exception(it.error)
            it.data
        }

    fun start() {
        val t = Thread(this, "native-tunnel")
        t.start()
    }

    fun events() = flow {
        val ev = events.receive()
        runCatching {
            emit(EventsJson.decodeFromString<Event>(ev))
        }.onFailure {
            Log.w(TAG, "failed to parse event: $ev", it)
        }
    }

    fun routes(): StateFlow<Sequence<Route>> = routeData
    fun addRoute(rt: String) {
        routes.compute(rt.toRoute()){ _, count -> (count ?: 0) + 1 }
    }
    fun delRoute(rt: String) {
        routes.compute(rt.toRoute()){ _, count ->
            if (count == null) null
            else if(count - 1 == 0) null
            else count - 1
        }
    }

    fun commitRoutes() {
        val rts = mutableListOf(defaultRoute)
        rts.addAll(routes.keys.filter { !defaultRoute.includes(it) })
        routeData.tryEmit(rts.asSequence())
    }

    companion object {
        private val TAG = Tunnel::class.simpleName
        // Used to load the 'tunnel' library on application startup.
        init {
            System.loadLibrary("tunnel")
        }
        @JvmStatic
        external fun tlsuvVersion(): String
        @JvmStatic
        external fun zitiSdkVersion(): String
        @JvmStatic
        external fun zitiTunnelVersion(): String

    }

    private var startTime: TimeMark? = null

    fun getUptime(): Duration =
        startTime?.elapsedNow() ?: Duration.ZERO


    private val active = AtomicBoolean(false)
    fun isActive() = active.get()
    fun startNetworkInterface(fd: ParcelFileDescriptor) {
        if (active.compareAndSet(false, true)) {
            startTime = TimeSource.Monotonic.markNow()
            startNetIf(fd.detachFd())
        }
    }

    fun stopNetworkInterface() {
        if (active.compareAndSet(true, false)) {
            stopNetIf()
        }
    }

    fun setupDNS(dns: String, range: String) {
        defaultRoute = range.toRoute()
        setDNSrange(dns, range)
        commitRoutes()
    }

    external fun initNative(app: String, version: String)
    external fun setDNSrange(dns: String, range: String)
    external fun setupIPC(cmdFD: Int, eventDF: Int)

    external fun executeCommand(cmd: String, data: String, ctx: Any)

    external override fun run()

    external fun startNetIf(fd: Int)
    external fun stopNetIf()
    external fun getUpRate(): Double
    external fun getDownRate(): Double
}