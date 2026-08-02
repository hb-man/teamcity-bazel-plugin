package bazel.messages

/**
 * Buffers the failure diagnostics of the invocation that is currently being read.
 *
 * Bazel restarts an invocation on its own after a transient remote cache error
 * (`REMOTE_CACHE_EVICTED`, exit code 39), which `--experimental_remote_cache_eviction_retries`
 * retries by default; the retry usually succeeds and `bazel` exits with 0. A TeamCity compilation
 * error is a build problem and cannot be retracted once written, so reporting a failure as soon as
 * it is seen permanently fails builds that Bazel went on to complete successfully.
 *
 * Diagnostics are therefore held until the invocation is known to be the last one: written out when
 * the event stream ends, dropped when Bazel restarts an invocation that failed the way it retries,
 * and reported when the next invocation starts if it ended any other way — a server handling builds
 * in sequence must not lose the earlier ones.
 *
 * The bounds keep the deferral from costing what immediate reporting did not: reports used to be
 * released as soon as they were written, so a build with very many failed actions could not
 * accumulate their output on the heap.
 */
class PendingInvocationDiagnostics {
    private val pending = mutableListOf<Diagnostic>()
    private var bufferedDetailChars = 0
    private var dropped = 0
    private var retriablyFailed = false

    /** [details] is only evaluated when there is room for it, since producing it reads files. */
    fun addCompilationError(
        summary: String,
        details: () -> String,
    ) = add { Diagnostic.CompilationError(summary, charged(details())) }

    fun addErrorMessage(
        text: String,
        hasPrefix: Boolean = true,
    ) = add { Diagnostic.ErrorMessage(charged(text), hasPrefix) }

    fun recordExitCode(code: Int) {
        synchronized(pending) { retriablyFailed = code == REMOTE_CACHE_EVICTED }
    }

    /**
     * Drops the diagnostics of an invocation that ended the way Bazel retries.
     *
     * Returns false when it ended some other way, which makes whatever follows a separate build
     * rather than a retry, and its diagnostics something to report rather than lose.
     */
    fun discardIfRetriablyFailed(writer: MessageWriter): Boolean {
        synchronized(pending) { if (!retriablyFailed) return false }
        discardSuperseded(writer)
        return true
    }

    /** For a restart established outside the event stream, which needs no exit code to confirm it. */
    fun discardSuperseded(writer: MessageWriter) {
        val discarded = synchronized(pending) { (pending.size + dropped).also { reset() } }
        if (discarded > 0) {
            writer.warning(
                "Bazel restarted the invocation; " +
                    "$discarded failure(s) reported by the superseded attempt are ignored.",
            )
        }
    }

    private inline fun add(diagnostic: () -> Diagnostic) {
        synchronized(pending) {
            if (pending.size >= MAX_BUFFERED_REPORTS) {
                dropped++
            } else {
                pending.add(diagnostic())
            }
        }
    }

    /** Charges [text] against the shared budget, truncating once it is spent. */
    private fun charged(text: String): String {
        val room = MAX_BUFFERED_DETAIL_CHARS - bufferedDetailChars
        val kept =
            when {
                room <= 0 -> OMITTED_DETAILS
                text.length > room -> text.take(room) + TRUNCATION_MARKER
                else -> text
            }
        bufferedDetailChars += kept.length
        return kept
    }

    fun flush(writer: MessageWriter) {
        val diagnostics: List<Diagnostic>
        val notReported: Int
        synchronized(pending) {
            diagnostics = pending.toList()
            notReported = dropped
            reset()
        }

        diagnostics.forEach {
            when (it) {
                is Diagnostic.CompilationError -> {
                    writer.compilationStarted(it.summary)
                    writer.error(it.details, hasPrefix = false)
                    writer.compilationFinished(it.summary)
                }

                is Diagnostic.ErrorMessage -> writer.error(it.text, hasPrefix = it.hasPrefix)
            }
        }

        if (notReported > 0) {
            writer.warning(
                "$notReported further failure(s) were not reported: " +
                    "more than $MAX_BUFFERED_REPORTS failures in a single invocation.",
            )
        }
    }

    private fun reset() {
        pending.clear()
        bufferedDetailChars = 0
        dropped = 0
        retriablyFailed = false
    }

    private sealed interface Diagnostic {
        /** Becomes a TeamCity build problem, which is what makes premature reporting unrecoverable. */
        data class CompilationError(
            val summary: String,
            val details: String,
        ) : Diagnostic

        data class ErrorMessage(
            val text: String,
            val hasPrefix: Boolean,
        ) : Diagnostic
    }

    private companion object {
        const val REMOTE_CACHE_EVICTED = 39
        const val MAX_BUFFERED_REPORTS = 500
        const val MAX_BUFFERED_DETAIL_CHARS = 4 * 1024 * 1024
        const val TRUNCATION_MARKER = "\n<truncated>"
        const val OMITTED_DETAILS = "<omitted: buffered failure output limit reached>"
    }
}
