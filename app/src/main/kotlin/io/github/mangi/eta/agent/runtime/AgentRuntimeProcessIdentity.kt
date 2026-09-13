package io.github.mangi.eta.agent.runtime

import java.util.UUID

internal object AgentRuntimeProcessIdentity {
    val id: String = UUID.randomUUID().toString()
}
