package io.github.mangi.eta.ui.components

internal fun chatConversationCompositionKey(conversationId: String?): String =
    conversationId?.let { "conversation:$it" } ?: "conversation:draft"
