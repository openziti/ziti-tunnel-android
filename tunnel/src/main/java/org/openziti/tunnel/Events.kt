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
