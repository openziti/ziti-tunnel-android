/*
 * Copyright (c) 2025 NetFoundry. All rights reserved.
 */

package org.openziti.tunnel

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MfaTest {
    @Test
    fun commandsSerializeWithSdkFieldNames() {
        assertEquals("""{"Identifier":"id1"}""", EnableMFA("id1").toJson())
        assertEquals("""{"Identifier":"id1","Code":"123456"}""", SubmitMFA("id1", "123456").toJson())
        assertEquals("""{"Identifier":"id1","Code":"123456"}""", VerifyMFA("id1", "123456").toJson())
        assertEquals("""{"Identifier":"id1","Code":"123456"}""", GetMFACodes("id1", "123456").toJson())
        assertEquals("""{"Identifier":"id1","Code":"123456"}""", GenerateMFACodes("id1", "123456").toJson())
    }

    @Test
    fun commandsUseMatchingNativeCommandNames() {
        assertEquals(CMD.EnableMFA, EnableMFA("id1").cmd)
        assertEquals(CMD.SubmitMFA, SubmitMFA("id1", "1").cmd)
        assertEquals(CMD.VerifyMFA, VerifyMFA("id1", "1").cmd)
        assertEquals(CMD.GetMFACodes, GetMFACodes("id1", "1").cmd)
        assertEquals(CMD.GenerateMFACodes, GenerateMFACodes("id1", "1").cmd)
    }

    @Test
    fun commandsCarryingSecretsAreMarkedSensitive() {
        assertTrue(EnableMFA("id1").sensitive)
        assertTrue(SubmitMFA("id1", "1").sensitive)
        assertTrue(VerifyMFA("id1", "1").sensitive)
        assertTrue(GetMFACodes("id1", "1").sensitive)
        assertTrue(GenerateMFACodes("id1", "1").sensitive)
        assertFalse(ListIdentities.sensitive)
        assertFalse(RefreshIdentity("id1").sensitive)
    }

    @Test
    fun parsesAuthChallengeEvent() {
        val ev = EventsJson.decodeFromString<Event>(
            """{"identifier":"id1","type":"MFAEvent","operation":"auth_challenge","operation_type":"auth_challenge"}"""
        )
        assertEquals(MFAEvent("id1", "auth_challenge"), ev)
    }

    @Test
    fun parsesEnrollmentRequiredEvent() {
        val ev = EventsJson.decodeFromString<Event>(
            """{"identifier":"id1","type":"MFAEvent","operation":"enrollment_required"}"""
        )
        assertEquals(MFAEvent("id1", "enrollment_required"), ev)
    }

    @Test
    fun parsesSuccessfulStatusEventWithoutStatusText() {
        val ev = EventsJson.decodeFromString<Event>(
            """{"identifier":"id1","type":"MFAStatusEvent","operation":"mfa_auth_status","operation_type":"mfa_auth_status","code":0}"""
        ) as MFAStatusEvent
        assertEquals("mfa_auth_status", ev.operation)
        assertNull(ev.status)
        assertTrue(ev.success)
    }

    @Test
    fun parsesFailedStatusEvent() {
        val ev = EventsJson.decodeFromString<Event>(
            """{"identifier":"id1","type":"MFAStatusEvent","operation":"enrollment_verification","status":"invalid code","code":-12}"""
        ) as MFAStatusEvent
        assertEquals("invalid code", ev.status)
        assertFalse(ev.success)
    }

    @Test
    fun statusEventIgnoresEnrollmentSecrets() {
        // the enrollment_challenge status event repeats the secret; the model must not keep it
        val ev = EventsJson.decodeFromString<Event>(
            """{"identifier":"id1","type":"MFAStatusEvent","operation":"enrollment_challenge",
               "provisioning_url":"otpauth://totp/x?secret=ABC","recovery_codes":["a","b"],"code":0}"""
        )
        assertFalse(ev.toString().contains("ABC"))
        assertFalse(ev.toString().contains("recovery"))
    }

    @Test
    fun parsesEnrollmentResult() {
        val json = """{"Identifier":"id1","IsVerified":false,
            "ProvisioningUrl":"otpauth://totp/ziti%20ctrl:user?secret=JBSWY3DPEHPK3PXP&issuer=ziti",
            "RecoveryCodes":["aaa","bbb"]}"""
        val res = ResultJson.decodeFromString<MfaEnrollment>(json)
        assertEquals("id1", res.identifier)
        assertFalse(res.isVerified)
        assertEquals(listOf("aaa", "bbb"), res.recoveryCodes)
        assertEquals("JBSWY3DPEHPK3PXP", res.secret)
    }

    @Test
    fun enrollmentWithoutRecoveryCodes() {
        val res = ResultJson.decodeFromString<MfaEnrollment>(
            """{"Identifier":"id1","IsVerified":false,"ProvisioningUrl":"otpauth://totp/x?secret=S"}"""
        )
        assertTrue(res.recoveryCodes.isNullOrEmpty())
    }

    @Test
    fun secretIsNullWhenUrlHasNone() {
        val res = MfaEnrollment("id1", false, "otpauth://totp/x?issuer=ziti", null)
        assertNull(res.secret)
    }

    @Test
    fun parsesRecoveryCodesResult() {
        val res = ResultJson.decodeFromString<MfaRecoveryCodes>(
            """{"Identifier":"id1","RecoveryCodes":["c1","c2","c3"]}"""
        )
        assertEquals(listOf("c1", "c2", "c3"), res.recoveryCodes)
    }

    @Test
    fun redactsProvisioningUrlAndRecoveryCodesInEventJson() {
        val json = """{"identifier":"id1","type":"MFAStatusEvent","operation":"enrollment_challenge",""" +
            """"provisioning_url":"otpauth://totp/x?secret=ABC","recovery_codes":["r1","r2"],"code":0}"""
        val out = json.redactMfaSecrets()
        assertFalse(out, out.contains("ABC"))
        assertFalse(out, out.contains("r1"))
        assertTrue(out, out.contains(""""operation":"enrollment_challenge""""))
        assertTrue(out, out.contains(""""identifier":"id1""""))
        // still valid JSON
        Json.parseToJsonElement(out)
    }

    @Test
    fun redactsProvisioningUrlAndRecoveryCodesInCommandResult() {
        val json = """{"Success":true,"Code":0,"Data":{"Identifier":"id1","IsVerified":false,""" +
            """"ProvisioningUrl":"otpauth://totp/x?secret=ABC","RecoveryCodes":["r1","r2"]}}"""
        val out = json.redactMfaSecrets()
        assertFalse(out, out.contains("ABC"))
        assertFalse(out, out.contains("r1"))
        assertTrue(out, out.contains(""""Success":true"""))
        Json.parseToJsonElement(out)
    }

    @Test
    fun redactionLeavesOtherJsonUntouched() {
        val json = """{"identifier":"id1","type":"ContextEvent","status":"OK"}"""
        assertEquals(json, json.redactMfaSecrets())
    }
}
