/*
 * Copyright (c) 2026 NetFoundry. All rights reserved.
 */

package org.openziti.tunnel.integ

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.openziti.log.NativeLog
import org.openziti.tunnel.ContextEvent
import org.openziti.tunnel.Enroll
import org.openziti.tunnel.Keychain
import org.openziti.tunnel.LoadIdentity
import org.openziti.tunnel.ServiceEvent
import org.openziti.tunnel.Tunnel
import org.openziti.tunnel.ZitiConfig
import timber.log.Timber
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * Enrolls a fresh identity against the quickstart controller and expects it to authenticate and see its service.
 * Driven by `:integ:integrationTest`, which passes the one-time enrollment JWT (`jwt`) and the service name (`service`).
 */
@RunWith(AndroidJUnit4::class)
class EnrollAndLoadTest {

    @Test
    fun enrollsAndLoadsIdentity() {
        val jwt = IntegArgs.require("jwt")
        val service = IntegArgs.require("service")

        Timber.plant(Timber.DebugTree(), NativeLog)
        val tunnel = Tunnel(ApplicationProvider.getApplicationContext<Application>())
        // the same setup as TunnelModel's init: the app's default DNS address and range, then start
        tunnel.setupDNS("100.64.0.2", "100.64.0.0/10")
        tunnel.start()
        // Touching Keychain.store runs Keychain's initializer, which registers the Android Keystore with the native
        // layer. The app does this in TunnelModel's init; without it enrollment falls back to a PEM key in the config.
        Keychain.store.aliases()

        val enrolled = tunnel.processCmd(Enroll(jwt = jwt, useKeychain = true)).get(60, TimeUnit.SECONDS)
        val config = Json.decodeFromJsonElement<ZitiConfig>(enrolled!!)
        tunnel.processCmd(LoadIdentity(config.identifier, config, false)).get(60, TimeUnit.SECONDS)

        val seen = mutableListOf<String>()
        var authenticated = false
        var serviceSeen = false
        try {
            runBlocking {
                withTimeout(60.seconds) {
                    // Not events().first { }: Tunnel.events() wraps emit() in runCatching, which swallows the abort
                    // exception first() uses to stop the collection, so such a flow never completes.
                    val done = CompletableDeferred<Unit>()
                    val collector = launch {
                        tunnel.events().collect { event ->
                            seen += event.toString()
                            if (event.identifier == config.identifier) {
                                if (event is ContextEvent && event.status == "OK") authenticated = true
                                if (event is ServiceEvent && event.addedServices.any { it.name == service }) serviceSeen = true
                            }
                            if (authenticated && serviceSeen) done.complete(Unit)
                        }
                    }
                    try {
                        done.await()
                    } finally {
                        collector.cancel()
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            fail(
                "identity ${config.identifier} did not authenticate and see service '$service' within 60 s " +
                    "(authenticated=$authenticated, serviceSeen=$serviceSeen); events seen: $seen"
            )
        }
    }
}
