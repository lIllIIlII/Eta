package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentContextSnapshot
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentToolBatchRecovery
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class AgentRuntimeSession(
    val runId: String,
    val controller: AgentRunController = AgentRunController(),
    eventSink: ((AgentEvent) -> Unit)? = null,
    resultSink: ((AgentRuntimeWire.RunResult) -> Unit)? = null,
    private val operation: String = AgentRuntimeWire.OP_CHAT,
) {
    private enum class State {
        RUNNING,
        COMMITTING,
        TERMINAL,
    }

    private val lock = ReentrantLock()
    private var latestTranscript: List<AgentModelClient.ConversationMessage> = emptyList()
    val transcript: List<AgentModelClient.ConversationMessage>
        get() = lock.withLock { latestTranscript }

    fun updateTranscript(messages: List<AgentModelClient.ConversationMessage>) = lock.withLock {
        if (state == State.RUNNING) latestTranscript = messages
    }

    private var latestContext: AgentContextSnapshot? = null
    val contextSnapshot: AgentContextSnapshot?
        get() = lock.withLock { latestContext }

    fun updateContext(snapshot: AgentContextSnapshot) = lock.withLock {
        if (state == State.RUNNING) latestContext = snapshot
    }
    private var state = State.RUNNING
    private val replayEvents = mutableListOf<AgentEvent>()
    private val subscribers = mutableListOf<Subscriber>()

    private data class Subscriber(
        val eventSink: (AgentEvent) -> Unit,
        val resultSink: (AgentRuntimeWire.RunResult) -> Unit,
    )

    init {
        if (eventSink != null || resultSink != null) {
            subscribers += Subscriber(
                eventSink = eventSink ?: {},
                resultSink = resultSink ?: {},
            )
        }
    }

    val isTerminal: Boolean
        get() = lock.withLock { state == State.TERMINAL }

    fun emit(event: AgentEvent): Boolean =
        lock.withLock {
            if (state != State.RUNNING) return false
            recordForReplay(event)
            subscribers.forEach { it.eventSink(event) }
            true
        }

    fun attach(
        eventSink: (AgentEvent) -> Unit,
        resultSink: (AgentRuntimeWire.RunResult) -> Unit,
        onReplayComplete: () -> Unit = {},
    ): Boolean = lock.withLock {
        if (state == State.TERMINAL) return false
        replayEvents.forEach(eventSink)
        onReplayComplete()
        subscribers += Subscriber(eventSink, resultSink)
        true
    }

    fun steer(text: String): Boolean =
        lock.withLock {
            if (state != State.RUNNING || operation != AgentRuntimeWire.OP_CHAT) return false
            controller.steer(text)
        }

    fun <T : AgentEvent> steer(
        text: String,
        eventFactory: () -> T,
    ): T? =
        lock.withLock {
            if (state != State.RUNNING || operation != AgentRuntimeWire.OP_CHAT || !controller.steer(text)) return null
            eventFactory().also { event ->
                recordForReplay(event)
                subscribers.forEach { it.eventSink(event) }
            }
        }

    private fun recordForReplay(event: AgentEvent) {
        val projected = event.recoveryProjection() ?: return
        if (projected !is AgentEvent.AssistantBlockDelta) {
            replayEvents += projected
            return
        }
        val previous = replayEvents.lastOrNull() as? AgentEvent.AssistantBlockDelta
        if (
            previous != null &&
            previous.round == projected.round &&
            previous.kind == projected.kind &&
            previous.index == projected.index
        ) {
            replayEvents[replayEvents.lastIndex] = previous.copy(
                deltaChars = previous.deltaChars + projected.deltaChars,
                delta = previous.delta + projected.delta,
            )
        } else {
            replayEvents += projected
        }
    }

    fun complete(
        result: AgentRuntimeWire.RunResult,
        beforePublish: () -> Unit = {},
    ): Boolean {
        lock.withLock {
            if (state != State.RUNNING) return false
            require(result.runId == runId) { "Result runId does not match the active session" }
            state = State.COMMITTING
        }
        val commitFailure = runCatching(beforePublish).exceptionOrNull()
        lock.withLock {
            state = State.TERMINAL
            subscribers.forEach { it.resultSink(result) }
            subscribers.clear()
            replayEvents.clear()
        }
        commitFailure?.let { throw it }
        return true
    }

    fun cancel(reason: String): Boolean {
        val result = lock.withLock {
            if (state != State.RUNNING) return false
            state = State.TERMINAL
            AgentRuntimeWire.RunResult(
                runId = runId,
                ok = false,
                content = "",
                error = reason,
                contextSnapshot = latestContext,
                transcript = AgentToolBatchRecovery.completeInterrupted(latestTranscript),
                operation = operation,
            )
        }
        controller.cancel()
        lock.withLock {
            subscribers.forEach { it.resultSink(result) }
            subscribers.clear()
            replayEvents.clear()
        }
        return true
    }
}
