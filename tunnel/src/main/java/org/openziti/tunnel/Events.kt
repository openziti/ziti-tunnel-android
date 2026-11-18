/*
 * Copyright (c) 2024 NetFoundry. All rights reserved.
 */

package org.openziti.tunnel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

val eventModule = SerializersModule {
    polymorphic(Event::class) {
        subclass(ContextEvent::class)
        subclass(ConfigEvent::class)
        subclass(ServiceEvent::class)
        subclass(ExtJWTEvent::class)
        subclass(RouterEvent::class)
        subclass(MFAEvent::class)
        subclass(MFAStatusEvent::class)
    }
}
val EventsJson = Json {
    ignoreUnknownKeys = true
    serializersModule = eventModule
}

@Serializable
sealed class Event {
    abstract val identifier: String
}

@Serializable @SerialName("ContextEvent")
data class ContextEvent(
    override val identifier: String,
    val status: String,
    val name: String? = null,
    val controller: String? = null,): Event()

@Serializable @SerialName("ConfigEvent")
data class ConfigEvent(
    override val identifier: String,
    val config: ZitiConfig,
): Event()

@Serializable @SerialName("ExtJWTEvent")
data class ExtJWTEvent(
    override val identifier: String,
    val status: String,
    val providers: List<JwtSigner> = emptyList(),
): Event()

/** `operation` values of [MFAEvent] and [MFAStatusEvent] */
object MfaOperation {
    /** the auth policy requires the identity to enroll a TOTP authenticator */
    const val ENROLLMENT_REQUIRED = "enrollment_required"
    /** the identity needs a TOTP code to complete authentication */
    const val AUTH_CHALLENGE = "auth_challenge"
    const val AUTH_STATUS = "mfa_auth_status"
    const val ENROLLMENT_VERIFICATION = "enrollment_verification"
}

/** The identity is waiting on the user: see [MfaOperation.ENROLLMENT_REQUIRED] and [MfaOperation.AUTH_CHALLENGE]. */
@Serializable @SerialName("MFAEvent")
data class MFAEvent(
    override val identifier: String,
    val operation: String,
): Event()

/**
 * Outcome of an MFA command. [status] is set only on failure.
 *
 * The SDK also puts the provisioning URL and recovery codes in the enrollment event.
 * They are deliberately not modeled here so they never end up in logs.
 */
@Serializable @SerialName("MFAStatusEvent")
data class MFAStatusEvent(
    override val identifier: String,
    val operation: String,
    val status: String? = null,
): Event() {
    val success: Boolean get() = status == null
}

@Serializable
data class JwtSigner (
    val name: String,
    val issuer: String? = null,
)

@Serializable
data class Service(
    val id: String,
    val name: String,
) {
    val interceptConfig: String
        get() = "intercept for $name" // TODO
}

@Serializable @SerialName("ServiceEvent")
data class ServiceEvent(
    override val identifier: String,
    @SerialName("added_services") val addedServices: List<Service> = emptyList(),
    @SerialName("removed_services") val removedServices: List<Service> = emptyList(),
): Event()

@Serializable
enum class RouterStatus {
    @SerialName("added") ADDED,
    @SerialName("connected") CONNECTED,
    @SerialName("disconnected") DISCONNECTED,
    @SerialName("removed") REMOVED,
}
@Serializable @SerialName("RouterEvent")
data class RouterEvent(
    override val identifier: String,
    val name: String,
    val status: RouterStatus,
): Event()
