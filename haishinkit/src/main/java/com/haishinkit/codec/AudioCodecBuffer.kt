package com.haishinkit.codec

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer

/**
 * Audio ring buffer for MediaCodec encoding.
 *
 * Optimized implementation based on HaishinKit.swift's AudioRingBuffer:
 * - Bulk copy operations for better performance
 * - Explicit skip tracking for jitter diagnostics (like Swift)
 * - Overflow detection and statistics
 * - Sample-count based timestamps
 */
internal class AudioCodecBuffer {
    var sampleRate: Int = 44100
    var channelCount: Int = 2
    var presentationTimestamp: Long = DEFAULT_PRESENTATION_TIMESTAMP
        private set

    // Ring buffer (contiguous sample storage)
    private var ringBuffer: ShortArray? = null
    private var head = 0  // Write position (in sample pairs)
    private var tail = 0  // Read position (in sample pairs)
    private var bufferCapacity = 0  // Total capacity in sample pairs

    // Sample-based timestamp tracking (like Swift's AudioTime)
    private var sampleTime: Long = 0  // Total samples rendered

    // Skip tracking (like Swift's skip counter for jitter absorption)
    private var skipSamples: Long = 0  // Samples filled with silence due to underflow

    // Buffering mode
    private var isBuffering = true
    private var bufferingStartTime = 0L
    private val minBufferFrames = 8

    // Statistics
    private var appendCount = 0
    private var renderCount = 0
    private var waitCount = 0
    private var overflowCount = 0L  // Samples dropped due to overflow
    private var totalAppendedPairs = 0L
    private var totalRenderedPairs = 0L

    // Reusable ShortBuffer for bulk operations
    private var tempShortBuffer: ShortBuffer? = null

    // Debug logging control
    var debugLogging = false

    /**
     * Available sample pairs in the ring buffer
     */
    val availableSamples: Int
        get() {
            val h = head
            val t = tail
            return if (h >= t) h - t else bufferCapacity - t + h
        }

    /**
     * Buffer fill ratio (0.0 to 1.0)
     */
    val fillRatio: Float
        get() = if (bufferCapacity > 0) availableSamples.toFloat() / bufferCapacity else 0f

    /**
     * Total skip samples (silence inserted due to underflow)
     */
    val totalSkipSamples: Long
        get() = skipSamples

    /**
     * Total overflow samples (dropped due to buffer full)
     */
    val totalOverflowSamples: Long
        get() = overflowCount

    /**
     * Initialize the ring buffer
     */
    private fun ensureBuffer() {
        if (ringBuffer == null) {
            bufferCapacity = BUFFER_COUNT * SAMPLES_PER_FRAME
            val totalShorts = bufferCapacity * channelCount
            ringBuffer = ShortArray(totalShorts)
            head = 0
            tail = 0
            sampleTime = 0
            skipSamples = 0
            if (debugLogging) {
                Log.d(TAG, "Ring buffer initialized: capacity=$bufferCapacity pairs, channels=$channelCount, totalShorts=$totalShorts")
            }
        }
    }

    /**
     * Append audio data to the ring buffer.
     * Optimized with bulk copy operations.
     */
    fun append(byteBuffer: ByteBuffer) {
        ensureBuffer()
        val buffer = ringBuffer ?: return

        val remaining = byteBuffer.remaining()
        if (remaining <= 0) return

        val sampleCount = remaining / 2  // Int16 = 2 bytes
        val pairCount = sampleCount / channelCount

        appendCount++
        totalAppendedPairs += pairCount

        byteBuffer.order(ByteOrder.nativeOrder())

        // Get or create ShortBuffer view for bulk operations
        val shortBuffer = byteBuffer.asShortBuffer()

        // Calculate available space
        val freeSpace = bufferCapacity - availableSamples - 1  // -1 to distinguish full from empty

        if (pairCount > freeSpace) {
            // Overflow - need to drop oldest data
            val overflow = pairCount - freeSpace
            overflowCount += overflow
            tail = (tail + overflow) % bufferCapacity
            if (debugLogging && (appendCount <= 5 || appendCount % 500 == 0)) {
                Log.w(TAG, "append #$appendCount: OVERFLOW dropped=$overflow pairs, total=$overflowCount")
            }
        }

        // Bulk copy data to ring buffer
        val samplesToWrite = minOf(pairCount, bufferCapacity - 1) * channelCount
        val writeStart = head * channelCount

        if (writeStart + samplesToWrite <= buffer.size) {
            // Contiguous write
            shortBuffer.get(buffer, writeStart, samplesToWrite)
        } else {
            // Wrap-around write
            val firstPart = buffer.size - writeStart
            shortBuffer.get(buffer, writeStart, firstPart)
            shortBuffer.get(buffer, 0, samplesToWrite - firstPart)
        }

        head = (head + minOf(pairCount, bufferCapacity - 1)) % bufferCapacity

        // Exit buffering mode
        if (isBuffering && appendCount >= minBufferFrames) {
            isBuffering = false
            if (debugLogging) {
                Log.d(TAG, "Buffering complete: $appendCount frames, available=$availableSamples")
            }
        }

        if (debugLogging && (appendCount <= 5 || appendCount % 200 == 0)) {
            Log.d(TAG, "append #$appendCount: pairs=$pairCount, avail=$availableSamples, fill=${(fillRatio * 100).toInt()}%")
        }
    }

    /**
     * Render audio data from ring buffer.
     * Optimized with bulk copy and explicit skip tracking.
     */
    fun render(byteBuffer: ByteBuffer): Int {
        ensureBuffer()
        val buffer = ringBuffer ?: return 0

        val start = byteBuffer.position()
        val remaining = byteBuffer.remaining()
        if (remaining <= 0) return 0

        renderCount++

        // Buffering mode
        if (isBuffering) {
            if (bufferingStartTime == 0L) {
                bufferingStartTime = System.currentTimeMillis()
            }
            if (debugLogging && (renderCount <= 10 || renderCount % 200 == 0)) {
                val elapsed = System.currentTimeMillis() - bufferingStartTime
                Log.d(TAG, "render #$renderCount: BUFFERING elapsed=${elapsed}ms, appendCount=$appendCount")
            }
            return 0
        }

        val requestedPairs = remaining / 2 / channelCount
        val available = availableSamples

        // Backpressure: wait if buffer too low
        val minRequired = SAMPLES_PER_FRAME / 2
        if (available < minRequired) {
            waitCount++
            if (debugLogging && (waitCount <= 10 || waitCount % 200 == 0)) {
                Log.d(TAG, "render #$renderCount: WAITING avail=$available < min=$minRequired")
            }
            return 0
        }

        byteBuffer.order(ByteOrder.nativeOrder())
        val outputShortBuffer = byteBuffer.asShortBuffer()

        // Read available data
        val pairsToRead = minOf(requestedPairs, available)
        val samplesToRead = pairsToRead * channelCount
        val readStart = tail * channelCount

        if (readStart + samplesToRead <= buffer.size) {
            // Contiguous read
            outputShortBuffer.put(buffer, readStart, samplesToRead)
        } else {
            // Wrap-around read
            val firstPart = buffer.size - readStart
            outputShortBuffer.put(buffer, readStart, firstPart)
            outputShortBuffer.put(buffer, 0, samplesToRead - firstPart)
        }

        tail = (tail + pairsToRead) % bufferCapacity
        totalRenderedPairs += pairsToRead

        // Fill remaining with silence (skip/jitter absorption)
        val silentPairs = requestedPairs - pairsToRead
        if (silentPairs > 0) {
            skipSamples += silentPairs
            val silentSamples = silentPairs * channelCount
            for (i in 0 until silentSamples) {
                outputShortBuffer.put(0)
            }
        }

        // Update ByteBuffer position
        byteBuffer.position(start + (requestedPairs * channelCount * 2))

        // Calculate timestamp
        presentationTimestamp = sampleTime * 1_000_000L / sampleRate
        sampleTime += requestedPairs

        // Statistics logging
        if (debugLogging && (renderCount <= 10 || renderCount % 200 == 0)) {
            val skipPct = if (totalRenderedPairs + skipSamples > 0) {
                (skipSamples * 100 / (totalRenderedPairs + skipSamples))
            } else 0
            Log.d(TAG, "render #$renderCount: read=$pairsToRead, silent=$silentPairs, skipPct=$skipPct%, ts=${presentationTimestamp / 1000}ms")
        }

        return requestedPairs * channelCount * 2
    }

    /**
     * Get buffer statistics for diagnostics
     */
    fun getStatistics(): Statistics {
        return Statistics(
            appendCount = appendCount,
            renderCount = renderCount,
            waitCount = waitCount,
            availableSamples = availableSamples,
            bufferCapacity = bufferCapacity,
            fillRatio = fillRatio,
            totalAppendedPairs = totalAppendedPairs,
            totalRenderedPairs = totalRenderedPairs,
            skipSamples = skipSamples,
            overflowCount = overflowCount
        )
    }

    fun clear() {
        head = 0
        tail = 0
        sampleTime = 0
        presentationTimestamp = DEFAULT_PRESENTATION_TIMESTAMP
        appendCount = 0
        renderCount = 0
        waitCount = 0
        skipSamples = 0
        overflowCount = 0
        totalAppendedPairs = 0
        totalRenderedPairs = 0
        isBuffering = true
        bufferingStartTime = 0L
        if (debugLogging) {
            Log.d(TAG, "Buffer cleared")
        }
    }

    /**
     * Buffer statistics for diagnostics
     */
    data class Statistics(
        val appendCount: Int,
        val renderCount: Int,
        val waitCount: Int,
        val availableSamples: Int,
        val bufferCapacity: Int,
        val fillRatio: Float,
        val totalAppendedPairs: Long,
        val totalRenderedPairs: Long,
        val skipSamples: Long,
        val overflowCount: Long
    ) {
        val skipPercentage: Float
            get() = if (totalRenderedPairs + skipSamples > 0) {
                skipSamples.toFloat() / (totalRenderedPairs + skipSamples) * 100
            } else 0f

        val overflowPercentage: Float
            get() = if (totalAppendedPairs > 0) {
                overflowCount.toFloat() / totalAppendedPairs * 100
            } else 0f

        override fun toString(): String {
            return "Statistics(fill=${(fillRatio * 100).toInt()}%, skip=${skipPercentage.toInt()}%, overflow=${overflowPercentage.toInt()}%)"
        }
    }

    companion object {
        private const val TAG = "AudioCodecBuffer"
        const val DEFAULT_PRESENTATION_TIMESTAMP = 0L
        const val SAMPLES_PER_FRAME = 1024
        const val BUFFER_COUNT = 16
    }
}
