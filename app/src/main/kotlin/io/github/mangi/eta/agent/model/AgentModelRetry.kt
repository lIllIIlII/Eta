package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController

internal class AgentModelRetry(
    private val waitBeforeRetry: (AgentRunController, Long) -> Unit = { controller, delay ->
        controller.awaitRetryDelay(delay)
    },
) {
    data class Result(val round: Int, val response: ProviderResponse, val apiKey: String)

    fun complete(
        initialRound: Int,
        request: ProviderRequest,
        provider: AgentProviderClient,
        controller: AgentRunController,
        onEvent: (AgentEvent) -> Unit,
        onProviderEvent: (Int, ProviderEvent) -> Unit,
        discardAttemptReasoning: () -> Unit,
    ): Result {
        var round = initialRound
        var retries = 0
        var activeRequest = request
        var keyIndex = 0
        while (true) {
            controller.throwIfCancelled()
            onEvent(AgentEvent.RoundStarted(round, activeRequest.messages.length()))
            var hostedToolStarted = false
            var callbackFailed = false
            try {
                val response = provider.complete(activeRequest, controller) { event ->
                    if (event is ProviderEvent.HostedToolStarted) hostedToolStarted = true
                    try {
                        onProviderEvent(round, event)
                    } catch (failure: Exception) {
                        callbackFailed = true
                        throw failure
                    }
                }
                return Result(round, response, activeRequest.config.apiKey)
            } catch (failure: Exception) {
                controller.throwIfCancelled()
                if (callbackFailed || Thread.currentThread().isInterrupted) throw failure
                val classified = AgentModelFailure.transport(failure) ?: throw failure
                if (hostedToolStarted) throw AgentModelFailure(
                    classified.code, false, classified.message.orEmpty(), classified, recoveryAllowed = false,
                )
                val keys = activeRequest.apiKeyPool
                if (isKeyFailoverCandidate(classified) && keyIndex < keys.lastIndex) {
                    keyIndex += 1
                    val nextKey = keys[keyIndex]
                    activeRequest = activeRequest.withApiKey(nextKey)
                    retries = 0
                    onEvent(
                        AgentEvent.ModelRetryScheduled(
                            round = round,
                            attempt = keyIndex + 1,
                            maxAttempts = keys.size,
                            delayMs = FAILOVER_DELAY_MS.toInt(),
                            reasonCode = FAILOVER_REASON_CODE,
                        )
                    )
                    waitBeforeRetry(controller, FAILOVER_DELAY_MS)
                    controller.throwIfCancelled()
                    discardAttemptReasoning()
                    round += 1
                    continue
                }
                if (!classified.retryable) throw classified
                if (retries == MAX_RETRIES) {
                    throw AgentModelFailure(
                        classified.code, false,
                        "${classified.message} 已重试 $MAX_RETRIES 次仍未恢复，已保留此前完成的工具结果。",
                        classified,
                    )
                }
                retries += 1
                val delayMs = BASE_DELAY_MS shl (retries - 1)
                onEvent(AgentEvent.ModelRetryScheduled(round, retries, MAX_RETRIES, delayMs.toInt(), classified.code))
                waitBeforeRetry(controller, delayMs)
                controller.throwIfCancelled()

                discardAttemptReasoning()
                round += 1
            }
        }
    }

    private fun isKeyFailoverCandidate(failure: AgentModelFailure): Boolean = when (failure.code) {
        "CONTEXT_OVERFLOW" -> false
        "HTTP_400", "HTTP_404", "HTTP_405", "HTTP_413", "HTTP_422" -> false
        "HTTP_401", "HTTP_402", "HTTP_403", "HTTP_429" -> true
        else -> failure.retryable
    }

    companion object {
        private const val MAX_RETRIES = 3
        private const val BASE_DELAY_MS = 2_000L
        private const val FAILOVER_DELAY_MS = 800L
        private const val FAILOVER_REASON_CODE = "API_KEY_FAILOVER"
    }
}
