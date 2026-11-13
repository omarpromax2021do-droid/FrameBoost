package com.framewise.app.gpu

data class PipelineMetricsSnapshot(
    val captureFps: Float = 0f,
    val outputFps: Float = 0f,
    val frameTimeMs: Float = 0f,
    val gpuPassTimeMs: Float = 0f,
    val pipelineLatencyMs: Float = 0f,
    val activePreset: String = "30->60 Console",
    val casSharpnessPercent: Int = 65,
    val effectiveMotionScalePercent: Int = 40,
    val smartGateStatus: String = "Active",
    val isInterpolating: Boolean = false,
    val capturedFramesTotal: Long = 0L,
    val renderedFramesTotal: Long = 0L
)

class MetricsTracker(
    private val rollingWindowNs: Long = DEFAULT_ROLLING_WINDOW_NS
) {
    private val lock = Any()
    private val captureTimestamps = ArrayDeque<Long>()
    private val outputTimestamps = ArrayDeque<Long>()
    private val gpuPassSamplesMs = ArrayDeque<Double>()
    private val latencySamplesMs = ArrayDeque<Double>()

    private var lastCaptureArrivalNs: Long = 0L
    private var lastFrameTimeMs: Double = 0.0
    private var capturedFramesTotal: Long = 0L
    private var renderedFramesTotal: Long = 0L

    private var casSharpnessPercent: Int = 65
    private var effectiveMotionScalePercent: Int = 40
    private var smartGateStatus: String = "Active"
    private var isInterpolating: Boolean = false

    fun recordCaptureFrame(arrivalTimeNs: Long) {
        synchronized(lock) {
            pruneOlderThan(captureTimestamps, arrivalTimeNs - rollingWindowNs)
            captureTimestamps.addLast(arrivalTimeNs)
            lastCaptureArrivalNs = arrivalTimeNs
            capturedFramesTotal++
        }
    }

    fun lastCaptureTimestampNs(): Long {
        synchronized(lock) {
            return lastCaptureArrivalNs
        }
    }

    fun updateRuntimeTelemetryState(
        casSharpnessPercent: Int,
        effectiveMotionScalePercent: Int,
        smartGateStatus: String,
        isInterpolating: Boolean
    ) {
        synchronized(lock) {
            this.casSharpnessPercent = casSharpnessPercent
            this.effectiveMotionScalePercent = effectiveMotionScalePercent
            this.smartGateStatus = smartGateStatus
            this.isInterpolating = isInterpolating
        }
    }

    fun latestRollingCaptureFps(nowNs: Long): Float {
        synchronized(lock) {
            pruneOlderThan(captureTimestamps, nowNs - rollingWindowNs)
            return computeFps(captureTimestamps)
        }
    }

    fun latestAverageGpuPassMs(): Float {
        synchronized(lock) {
            return if (gpuPassSamplesMs.isEmpty()) 0f else gpuPassSamplesMs.average().toFloat()
        }
    }

    fun recordOutputFrame(
        swapCompletionNs: Long,
        gpuShaderTimeMs: Double,
        captureSourceNs: Long
    ) {
        synchronized(lock) {
            pruneOlderThan(outputTimestamps, swapCompletionNs - rollingWindowNs)
            val prevOutputNs = outputTimestamps.lastOrNull()
            if (prevOutputNs != null && swapCompletionNs > prevOutputNs) {
                lastFrameTimeMs = (swapCompletionNs - prevOutputNs) / 1_000_000.0
            }
            outputTimestamps.addLast(swapCompletionNs)
            renderedFramesTotal++

            if (gpuShaderTimeMs >= 0.0) {
                if (gpuPassSamplesMs.size >= MAX_METRIC_SAMPLES) {
                    gpuPassSamplesMs.removeFirst()
                }
                gpuPassSamplesMs.addLast(gpuShaderTimeMs)
            }

            if (captureSourceNs > 0L && swapCompletionNs >= captureSourceNs) {
                val latencyMs = (swapCompletionNs - captureSourceNs) / 1_000_000.0
                if (latencySamplesMs.size >= MAX_METRIC_SAMPLES) {
                    latencySamplesMs.removeFirst()
                }
                latencySamplesMs.addLast(latencyMs)
            }
        }
    }

    fun snapshot(nowNs: Long, activePreset: String): PipelineMetricsSnapshot {
        synchronized(lock) {
            val cutoff = nowNs - rollingWindowNs
            pruneOlderThan(captureTimestamps, cutoff)
            pruneOlderThan(outputTimestamps, cutoff)

            val captureFps = computeFps(captureTimestamps)
            val outputFps = computeFps(outputTimestamps)
            val avgGpuMs = if (gpuPassSamplesMs.isEmpty()) 0.0 else gpuPassSamplesMs.average()
            val avgLatencyMs = if (latencySamplesMs.isEmpty()) 0.0 else latencySamplesMs.average()

            return PipelineMetricsSnapshot(
                captureFps = captureFps,
                outputFps = outputFps,
                frameTimeMs = if (outputFps > 0f) lastFrameTimeMs.toFloat() else 0f,
                gpuPassTimeMs = avgGpuMs.toFloat(),
                pipelineLatencyMs = avgLatencyMs.toFloat(),
                activePreset = activePreset,
                casSharpnessPercent = casSharpnessPercent,
                effectiveMotionScalePercent = effectiveMotionScalePercent,
                smartGateStatus = smartGateStatus,
                isInterpolating = isInterpolating,
                capturedFramesTotal = capturedFramesTotal,
                renderedFramesTotal = renderedFramesTotal
            )
        }
    }

    fun reset() {
        synchronized(lock) {
            captureTimestamps.clear()
            outputTimestamps.clear()
            gpuPassSamplesMs.clear()
            latencySamplesMs.clear()
            lastCaptureArrivalNs = 0L
            lastFrameTimeMs = 0.0
            capturedFramesTotal = 0L
            renderedFramesTotal = 0L
            isInterpolating = false
            smartGateStatus = "Active"
        }
    }

    private fun pruneOlderThan(queue: ArrayDeque<Long>, cutoffNs: Long) {
        while (queue.isNotEmpty() && queue.first() < cutoffNs) {
            queue.removeFirst()
        }
    }

    private fun computeFps(timestamps: ArrayDeque<Long>): Float {
        if (timestamps.size < 2) return 0f
        val spanNs = timestamps.last() - timestamps.first()
        if (spanNs <= 0L) return 0f
        return ((timestamps.size - 1) * 1_000_000_000.0 / spanNs).toFloat()
    }

    companion object {
        const val DEFAULT_ROLLING_WINDOW_NS = 500_000_000L
        private const val MAX_METRIC_SAMPLES = 30
    }
}
