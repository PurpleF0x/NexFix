package com.nex.assistant

data class NexResponse(
    val type: String,
    val response: String?,
    val action: String? = null,
    val metadata: Map<String, String>? = null
)
