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
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicLong

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
    val ca: String,
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

/**
 * @param sensitive the command or its result carries secrets (codes, keys) and must not be logged
 */
@Serializable sealed class TunnelCommand(
    @Transient val cmd: CMD = CMD.Status,
    @Transient val id: Long = counter.incrementAndGet(),
    @Transient val sensitive: Boolean = false) {
    companion object {
        val counter = AtomicLong(0)
    }
}
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

@Serializable data class ExternalAuth(
    @SerialName("Identifier") val identifier: String,
    @SerialName("Provider") val provider: String? = null,
): TunnelCommand(CMD.ExternalAuth)

@Serializable data class ExtAuthResult(
    val identifier: String,
    val url: String,
)

@Serializable data class EnableMFA(
    @SerialName("Identifier") val identifier: String,
): TunnelCommand(CMD.EnableMFA, sensitive = true)

@Serializable data class VerifyMFA(
    @SerialName("Identifier") val identifier: String,
    @SerialName("Code") val code: String,
): TunnelCommand(CMD.VerifyMFA, sensitive = true)

@Serializable data class SubmitMFA(
    @SerialName("Identifier") val identifier: String,
    @SerialName("Code") val code: String,
): TunnelCommand(CMD.SubmitMFA, sensitive = true)

@Serializable data class GetMFACodes(
    @SerialName("Identifier") val identifier: String,
    @SerialName("Code") val code: String,
): TunnelCommand(CMD.GetMFACodes, sensitive = true)

@Serializable data class GenerateMFACodes(
    @SerialName("Identifier") val identifier: String,
    @SerialName("Code") val code: String,
): TunnelCommand(CMD.GenerateMFACodes, sensitive = true)

/** Result of [EnableMFA]. [provisioningUrl] is an `otpauth://` URL. */
@Serializable data class MfaEnrollment(
    @SerialName("Identifier") val identifier: String,
    @SerialName("IsVerified") val isVerified: Boolean = false,
    @SerialName("ProvisioningUrl") val provisioningUrl: String,
    @SerialName("RecoveryCodes") val recoveryCodes: List<String>? = null,
) {
    /** The shared TOTP secret, for authenticators that cannot take the provisioning URL. */
    val secret: String?
        get() = provisioningUrl.substringAfter('?', "").split('&')
            .firstOrNull { it.startsWith("secret=") }
            ?.substringAfter('=')
            ?.let { URLDecoder.decode(it, "UTF-8") }
}

/** Result of [GetMFACodes] and [GenerateMFACodes]. */
@Serializable data class MfaRecoveryCodes(
    @SerialName("Identifier") val identifier: String,
    @SerialName("RecoveryCodes") val recoveryCodes: List<String>? = null,
)

@Serializable data class Dump(
    @SerialName("Identifier") val identifier: String
): TunnelCommand(CMD.ZitiDump)

/**
 * Data of a [Dump] response (tunnel SDK >= 1.22.1): [dumps] holds the text dump of each identity by identifier.
 * [files] lists what the tunneler also wrote to disk, which it only does on Windows.
 */
@Serializable data class DumpResult(
    @SerialName("Files") val files: List<String> = emptyList(),
    @SerialName("Dumps") val dumps: Map<String, String> = emptyMap(),
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun from(data: JsonElement): DumpResult = json.decodeFromJsonElement(serializer(), data)
    }
}

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
    val url: String? = null,
    val jwt: String? = null,
    val key: String? = null,
    val cert: String? = null,
    val useKeychain: Boolean = false,
): TunnelCommand(CMD.Enroll)

@Serializable data class RemoveIdentity(
    @SerialName("Identifier") val identifier: String
): TunnelCommand(CMD.RemoveIdentity)

@Serializable data class TunnelResult(
    @SerialName("Success") val success: Boolean,
    @SerialName("Code") val code: Int,
    @SerialName("Error") val error: String? = null,
    @SerialName("Data") val data: JsonElement? = null,
)

inline fun <reified C: TunnelCommand> C.toJson() = Json.encodeToString(this)

/** For decoding command results; tolerates fields added by newer SDKs. */
val ResultJson = Json { ignoreUnknownKeys = true }