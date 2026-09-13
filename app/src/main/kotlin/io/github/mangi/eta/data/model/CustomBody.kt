package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class CustomBody(
    val key: String,
    val value: JsonElement
)
