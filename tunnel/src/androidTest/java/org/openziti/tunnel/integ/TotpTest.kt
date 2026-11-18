/*
 * Copyright (c) 2026 NetFoundry. All rights reserved.
 */

package org.openziti.tunnel.integ

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.openziti.log.NativeLog
import org.openziti.tunnel.ContextEvent
import org.openziti.tunnel.EnableMFA
import org.openziti.tunnel.Enroll
import org.openziti.tunnel.Event
import org.openziti.tunnel.GenerateMFACodes
import org.openziti.tunnel.GetMFACodes
import org.openziti.tunnel.Keychain
import org.openziti.tunnel.LoadIdentity
import org.openziti.tunnel.MFAEvent
import org.openziti.tunnel.MFAStatusEvent
import org.openziti.tunnel.MfaEnrollment
import org.openziti.tunnel.MfaOperation
import org.openziti.tunnel.MfaRecoveryCodes
import org.openziti.tunnel.OnOffCommand
import org.openziti.tunnel.ResultJson
import org.openziti.tunnel.ServiceEvent
import org.openziti.tunnel.SubmitMFA
import org.openziti.tunnel.Tunnel
import org.openziti.tunnel.TunnelCommand
import org.openziti.tunnel.VerifyMFA
import org.openziti.tunnel.ZitiConfig
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * TOTP against the quickstart controller, with an identity on an auth policy that requires a TOTP code. Driven by
 * `:integ:integrationTest`, which passes the one-time enrollment JWT (`jwt`) and the service name (`service`).
 * Each test method runs in its own instrumentation process, so each one owns the process-wide native tunnel.
 */
@RunWith(AndroidJUnit4::class)
class TotpTest {

    @Test
    fun enrollsTotpAndAuthenticates() = session { s ->
        val enrollment = s.enrollTotp()
        assertTrue(enrollment.provisioningUrl, enrollment.provisioningUrl.startsWith("otpauth://totp/"))
        assertTrue("no recovery codes with the enrollment", !enrollment.recoveryCodes.isNullOrEmpty())

        s.awaitAuthenticated(after = s.verifyMark)
        s.awaitService(after = s.verifyMark)
    }

    @Test
    fun submitsTotpCodeAtLogin() = session { s ->
        val secret = s.enrollTotp().secret!!
        s.awaitAuthenticated(after = s.verifyMark)

        // log in again: the identity is enrolled now, so the controller asks for a code
        var mark = s.mark()
        s.run(OnOffCommand(s.id, false))
        s.run(OnOffCommand(s.id, true))
        s.awaitMfa(MfaOperation.AUTH_CHALLENGE, after = mark)

        // a wrong code is refused, and the prompt stays open for another try
        val valid = Totp.code(secret)
        val wrong = if (valid == "000000") "000001" else "000000"
        mark = s.mark()
        assertThrows(ExecutionException::class.java) { s.run(SubmitMFA(s.id, wrong)) }
        val refused = s.await("a refused code", after = mark) {
            it is MFAStatusEvent && it.operation == MfaOperation.AUTH_STATUS && !it.success
        } as MFAStatusEvent
        assertNotNull(refused.status)

        mark = s.mark()
        s.run(SubmitMFA(s.id, valid))
        // the SDK also raises a ContextEvent while authentication is only partial: the accepted code is the proof
        s.await("an accepted code", after = mark) {
            it is MFAStatusEvent && it.operation == MfaOperation.AUTH_STATUS && it.success
        }
        s.awaitAuthenticated(after = mark)
    }

    @Test
    fun readsAndReplacesRecoveryCodes() = session { s ->
        val enrollment = s.enrollTotp()
        val secret = enrollment.secret!!
        s.awaitAuthenticated(after = s.verifyMark)

        val issued = enrollment.recoveryCodes.orEmpty().toSet()
        assertTrue("no recovery codes with the enrollment", issued.isNotEmpty())

        val read = s.run(GetMFACodes(s.id, Totp.code(secret))).decode<MfaRecoveryCodes>()
        assertEquals(issued, read.recoveryCodes.orEmpty().toSet())

        val replaced = s.run(GenerateMFACodes(s.id, Totp.code(secret))).decode<MfaRecoveryCodes>()
            .recoveryCodes.orEmpty().toSet()
        assertTrue("no new recovery codes", replaced.isNotEmpty())
        assertNotEquals(issued, replaced)

        val reread = s.run(GetMFACodes(s.id, Totp.code(secret))).decode<MfaRecoveryCodes>()
        assertEquals(replaced, reread.recoveryCodes.orEmpty().toSet())
    }

    private fun session(block: (TotpSession) -> Unit) {
        val session = TotpSession(IntegArgs.require("jwt"), IntegArgs.require("service"))
        try {
            block(session)
        } finally {
            session.close()
        }
    }
}

private inline fun <reified T> JsonElement?.decode(): T = ResultJson.decodeFromJsonElement<T>(this!!)

/** A native tunnel with one freshly enrolled and loaded identity, and the events it raises. */
private class TotpSession(jwt: String, private val service: String) : AutoCloseable {
    val tunnel: Tunnel
    val config: ZitiConfig
    private val scope = CoroutineScope(Dispatchers.Default)
    private val seen = CopyOnWriteArrayList<Event>()

    /** [mark] taken just before the enrollment was verified: what comes after it is the outcome of the verification. */
    var verifyMark = 0
        private set

    val id: String get() = config.identifier

    init {
        Timber.plant(Timber.DebugTree(), NativeLog)
        tunnel = Tunnel(ApplicationProvider.getApplicationContext<Application>())
        // the same setup as TunnelModel's init: the app's default DNS address and range, then start
        tunnel.setupDNS("100.64.0.2", "100.64.0.0/10")
        tunnel.start()
        // registers the Android Keystore with the native layer, as TunnelModel's init does for the app
        Keychain.store.aliases()

        val enrolled = tunnel.processCmd(Enroll(jwt = jwt, useKeychain = true)).get(60, TimeUnit.SECONDS)
        config = Json.decodeFromJsonElement<ZitiConfig>(enrolled!!)
        // collect before the identity loads, so its first events are not missed
        scope.launch {
            tunnel.events().collect { if (it.identifier == config.identifier) seen += it }
        }
        tunnel.processCmd(LoadIdentity(config.identifier, config, false)).get(60, TimeUnit.SECONDS)
    }

    inline fun <reified C : TunnelCommand> run(cmd: C): JsonElement? =
        tunnel.processCmd(cmd).get(60, TimeUnit.SECONDS)

    /** Position in the event log: pass it as `after` to wait only for what happens from here on. */
    fun mark(): Int = seen.size

    fun await(what: String, after: Int = 0, timeout: Duration = 60.seconds, match: (Event) -> Boolean): Event {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        while (true) {
            seen.drop(after).firstOrNull(match)?.let { return it }
            if (System.nanoTime() > deadline) {
                fail("timed out after $timeout waiting for $what; events seen: ${seen.drop(after)}")
            }
            Thread.sleep(100)
        }
    }

    fun awaitMfa(operation: String, after: Int = 0) =
        await("MFA event '$operation'", after) { it is MFAEvent && it.operation == operation }

    fun awaitAuthenticated(after: Int = 0) =
        await("authentication of $id", after) { it is ContextEvent && it.status == "OK" }

    fun awaitService(after: Int = 0) =
        await("service '$service'", after) { it is ServiceEvent && it.addedServices.any { s -> s.name == service } }

    /** Waits for the enrollment prompt, enrolls TOTP and verifies it with a code made from the secret. */
    fun enrollTotp(): MfaEnrollment {
        awaitMfa(MfaOperation.ENROLLMENT_REQUIRED)
        val enrollment = run(EnableMFA(id)).decode<MfaEnrollment>()
        val secret = requireNotNull(enrollment.secret) { "no secret in the provisioning URL" }

        verifyMark = mark()
        run(VerifyMFA(id, Totp.code(secret)))
        await("the enrollment to be verified", after = verifyMark) {
            it is MFAStatusEvent && it.operation == MfaOperation.ENROLLMENT_VERIFICATION && it.success
        }
        return enrollment
    }

    override fun close() = scope.cancel()
}
