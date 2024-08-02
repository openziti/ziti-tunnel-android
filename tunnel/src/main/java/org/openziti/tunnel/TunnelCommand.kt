/*
 * Copyright (c) 2024 NetFoundry. All rights reserved.
 */

package org.openziti.tunnel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject

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
    RemoveIdentity,
    StatusChange,
    AddIdentity,
    ExternalAuth,
}

@Serializable data class ZitiID (
    val cert: String,
    val key: String,
    val ca: String
)

@Serializable data class ZitiConfig(
    @SerialName("ztAPI") val controller: String,
    @SerialName("ztAPIs") val controllers: List<String>?,
    val id: ZitiID,
)

@Serializable sealed class TunnelCommand(@Transient val cmd: CMD = CMD.Status)
@Serializable data object ListIdentities: TunnelCommand(CMD.ListIdentities)

@Serializable data class OnOffCommand(
    @SerialName("Identifier") val identifier: String,
    val on: Boolean
) : TunnelCommand(CMD.IdentityOnOff)

@Serializable data class LoadIdentity(
    @SerialName("Identifier") val identifier: String,
    @SerialName("Config") val config: ZitiConfig,
): TunnelCommand(CMD.LoadIdentity)

@Serializable data class TunnelCommandWrapper(val command: CMD, val data: TunnelCommand)

@Serializable data class TunnelResult(
    @SerialName("Success") val success: Boolean,
    @SerialName("Code") val code: Int,
    @SerialName("Error") val error: String? = null,
    @SerialName("Data") val data: JsonObject? = null,
)
