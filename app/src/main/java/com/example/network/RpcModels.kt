package com.example.network

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RpcRequest(
    val id: String,
    val type: String,
    val payload: Map<String, Any>? = null
)

@JsonClass(generateAdapter = true)
data class RpcResponse(
    val id: String,
    val ok: Boolean,
    val error: String? = null,
    val payload: Map<String, Any>? = null
)
