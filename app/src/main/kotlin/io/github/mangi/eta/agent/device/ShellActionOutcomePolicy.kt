package io.github.mangi.eta.agent.device

internal object ShellActionOutcomePolicy {
    enum class Outcome {
        SUCCEEDED,
        FAILED,
        TIMED_OUT,
    }

    fun classify(exitCode: Int): Outcome = when (exitCode) {
        0 -> Outcome.SUCCEEDED
        PROCESS_TIMEOUT_EXIT_CODE -> Outcome.TIMED_OUT
        else -> Outcome.FAILED
    }

    const val PROCESS_TIMEOUT_EXIT_CODE = -2
}
