package bazel.handlers

import bazel.Verbosity
import bazel.atLeast
import bazel.handlers.grpc.BuildEnqueuedHandler
import bazel.handlers.grpc.BuildFinishedHandler
import bazel.handlers.grpc.ComponentStreamFinishedHandler
import bazel.handlers.grpc.ConsoleOutputHandler
import bazel.handlers.grpc.InvocationAttemptFinishedHandler
import bazel.handlers.grpc.InvocationAttemptStartedHandler
import bazel.handlers.grpc.NotProcessedEventHandler
import bazel.handlers.grpc.PackedBazelEventHandler
import bazel.messages.MessageWriter

class GrpcEventHandlerChain : GrpcEventHandler {
    private val buildEventHandlerChain = BuildEventHandlerChain()

    private val handlers =
        listOf(
            BuildEnqueuedHandler(),
            InvocationAttemptStartedHandler(),
            InvocationAttemptFinishedHandler(),
            PackedBazelEventHandler(buildEventHandlerChain),
            BuildFinishedHandler(),
            ComponentStreamFinishedHandler(),
            ConsoleOutputHandler(),
        )

    override fun handle(ctx: GrpcEventHandlerContext): Boolean {
        handlers.firstOrNull { it.handle(ctx) } ?: NotProcessedEventHandler().handle(ctx)

        if (!ctx.event.hasBazelEvent() && ctx.verbosity.atLeast(Verbosity.Diagnostic)) {
            ctx.writer.trace(ctx.event.toString(), hasPrefix = false)
        }

        return true
    }

    fun flushPendingDiagnostics(writer: MessageWriter) = buildEventHandlerChain.flushPendingDiagnostics(writer)
}
