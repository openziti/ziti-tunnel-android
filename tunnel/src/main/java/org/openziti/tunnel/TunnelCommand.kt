/*
 * Copyright (c) 2024 NetFoundry. All rights reserved.
 */

package org.openziti.tunnel

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.net.URI

enum class CMD {
    ZitiDump,
    LoadIdentity,
    ListIdentities,
    IdentityOnOff,
    EnableMFA,
    SubmitMFA,
    VerifyMFA,
    RemoveMFA,
    GenerateMFACodes,
    GetMFACodes,
    GetMetrics,
    SetLogLevel,
    UpdateTunIpv4,
    ServiceControl,
    Status,
    RefreshIdentity,
    RemoveIdentity,
    StatusChange,
    AddIdentity,
    ExternalAuth,
    SetUpstreamDNS,
    Enroll,
}

@Serializable data class ZitiID (
    val cert: String? = null,
    val key: String? = null,
    val ca: String
)

@Serializable data class ZitiConfig(
    @SerialName("ztAPI") val controller: String,
    @SerialName("ztAPIs") val controllers: List<String> = emptyList(),
    val id: ZitiID,
) {
    val identifier: String = if (id.key != null)
        URI(id.key.removePrefix("keychain:")).let { it.userInfo ?: it.path.removePrefix("/") }
    else
        URI(controller).host.replace(".", "_")
}

@Serializable sealed class TunnelCommand(@Transient val cmd: CMD = CMD.Status)
@Serializable data object ListIdentities: TunnelCommand(CMD.ListIdentities)

@Serializable data class OnOffCommand(
    @SerialName("Identifier") val identifier: String,
    @SerialName("OnOff") val on: Boolean
) : TunnelCommand(CMD.IdentityOnOff)

@Serializable data class RefreshIdentity(
    @SerialName("Identifier") val identifier: String,
): TunnelCommand(CMD.RefreshIdentity)

@Serializable data class LoadIdentity(
    @SerialName("Identifier") val identifier: String,
    @SerialName("Config") val config: ZitiConfig,
    @SerialName("Disabled") val disabled: Boolean,
): TunnelCommand(CMD.LoadIdentity)

@Serializable data class Dump(
    @SerialName("Identifier") val identifier: String
): TunnelCommand(CMD.ZitiDump)

@Serializable data class Upstream(
    val host: String,
    val port: Int = 53
)

class SetUpstreamSerializer: KSerializer<SetUpstreamDNS> {
    private val delegate = ListSerializer(Upstream.serializer())
    override val descriptor = delegate.descriptor
    override fun deserialize(decoder: Decoder): SetUpstreamDNS {
        val upstreams = decoder.decodeSerializableValue(delegate)
        return SetUpstreamDNS(upstreams)
    }

    override fun serialize(encoder: Encoder, value: SetUpstreamDNS) {
        encoder.encodeSerializableValue(delegate, value.upstreams)
    }
}

@Serializable(with = SetUpstreamSerializer::class)
data class SetUpstreamDNS(
    val upstreams: List<Upstream> = emptyList()
): TunnelCommand(CMD.SetUpstreamDNS)

@Serializable data class Enroll(
    val jwt: String,
    val key: String? = null,
    val cert: String? = null,
    val useKeychain: Boolean = false,
): TunnelCommand(CMD.Enroll)

@Serializable data class TunnelResult(
    @SerialName("Success") val success: Boolean,
    @SerialName("Code") val code: Int,
    @SerialName("Error") val error: String? = null,
    @SerialName("Data") val data: JsonElement? = null,
)

inline fun <reified C: TunnelCommand> C.toJson() = Json.encodeToString(this)