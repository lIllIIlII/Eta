package io.github.mangi.eta.agent.tool

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

internal object AgentToolApprovalGate {

    const val TIMEOUT_MILLIS = 120_000L

    data class Request(
        val id: String,
        val toolName: String,
        val summary: String,
        val manualControl: Boolean = false,
    )

    private val pendingRequest = AtomicReference<Request?>(null)
    private val deferredRef = AtomicReference<CompletableDeferred<Boolean>?>(null)

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    @OptIn(FlowPreview::class)
    suspend fun await(toolName: String, summary: String, manualControl: Boolean = false): Boolean {
        val request = Request(
            id = UUID.randomUUID().toString(),
            toolName = toolName,
            summary = summary.take(200),
            manualControl = manualControl,
        )
        val deferred = CompletableDeferred<Boolean>()
        deferredRef.set(deferred)
        pendingRequest.set(request)
        _pending.value = request
        try {
            return withTimeoutOrNull(TIMEOUT_MILLIS) { deferred.await() } ?: false
        } finally {
            deferredRef.compareAndSet(deferred, null)
            if (pendingRequest.compareAndSet(request, null)) {
                _pending.value = null
            }
        }
    }

    fun respond(requestId: String, approved: Boolean) {
        val current = pendingRequest.get() ?: return
        if (current.id != requestId) return
        deferredRef.get()?.complete(approved)
        if (pendingRequest.compareAndSet(current, null)) {
            _pending.value = null
        }
    }

    fun currentSummary(argsJson: String): String = argsJson.take(200)
}
