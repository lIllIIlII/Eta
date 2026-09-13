package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

@Serializable
data class CustomHeader(
    val name: String,
    val value: String
)
