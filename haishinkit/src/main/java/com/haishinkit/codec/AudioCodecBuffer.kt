package com.haishinkit.codec

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Audio ring buffer for MediaCodec encoding.
 *
 * Based on HaishinKit.swift's AudioRingBuffer implementation:
 * - Simple ring buffer for jitter absorption
 * - Sample-count based timestamps (not real-time based)
 * - When buffer runs low, fills with silence (no time-stretching)
 */
internal class AudioCodecBuffer {
    var sampleRate: Int = 44100
    var channelCount: Int = 2
    var presentationTimestamp: Long = DEFAULT_PRESENTATION_TIMESTAMP
        private set

    // Ring buffer (contiguous sample storage)
    // Capacity: 16 × 1024 samples (like Swift's bufferCounts = 16)
    private var ringBuffer: ShortArray? = null
    private var head = 0  // Write position (in sample pairs for stereo)
    private var tail = 0  // Read position (in sample pairs for stereo)
    private var bufferCapacity = 0  // Total capacity in sample pairs

    // Sample-based timestamp tracking (like Swift's AudioTime)
    private var sampleTime: Long = 0  // Total samples rendered

    // Buffering mode: wait for initial data before starting encoding
    private var isBuffering = true
    private var bufferingStartTime = 0L
    private val minBufferFrames = 8  // Wait for at least 8 frames (~186ms) before starting

    // Debug counters
    private var appendCount = 0
    private var renderCount = 0
    private var waitCount = 0
    private var silentSamplePairs = 0L
    private var realSamplePairs = 0L

    /**
     * Available sample pairs in the ring buffer
     */
    private val availableSamples: Int
        get() {
            val h = head
            val t = tail
            return if (h >= t) {
                h - t
            } else {
                bufferCapacity - t + h
            }
        }

    /**
     * Initialize or resize the ring buffer
     */
    private fun ensureBuffer() {
        if (ringBuffer == null) {
            // 16 frames buffer like Swift version
            bufferCapacity = BUFFER_COUNT * SAMPLES_PER_FRAME
            val totalShorts = bufferCapacity * channelCount
            ringBuffer = ShortArray(totalShorts)
            head = 0
            tail = 0
            sampleTime = 0
            Log.d(TAG, "Ring buffer initialized: capacity=$bufferCapacity sample-pairs, channels=$channelCount")
        }
    }

    /**
     * Append audio data to the ring buffer.
     * Input is Int16 interleaved PCM in a ByteBuffer.
     */
    fun append(byteBuffer: ByteBuffer) {
        ensureBuffer()
        val buffer = ringBuffer ?: return

        val remaining = byteBuffer.remaining()
        if (remaining <= 0) return

        val sampleCount = remaining / 2  // Int16 = 2 bytes
        val pairCount = sampleCount / channelCount

        appendCount++

        byteBuffer.order(ByteOrder.nativeOrder())

        for (pairIdx in 0 until pairCount) {
            val writePos = head * channelCount

            // Check for overflow
            val nextHead = (head + 1) % bufferCapacity
            if (nextHead == tail) {
                // Overflow - advance tail (drop oldest)
                tail = (tail + 1) % bufferCapacity
            }

            for (ch in 0 until channelCount) {
                if (byteBuffer.remaining() >= 2) {
                    buffer[writePos + ch] = byteBuffer.short
                }
            }
            head = nextHead
        }

        // Check if we have enough data to exit buffering mode
        if (isBuffering && appendCount >= minBufferFrames) {
            isBuffering = false
            Log.d(TAG, "Buffering complete: $appendCount frames buffered, available=${availableSamples}")
        }

        if (appendCount <= 5 || appendCount % 100 == 0) {
            Log.d(TAG, "append #$appendCount: pairs=$pairCount, available=${availableSamples}, head=$head, tail=$tail, buffering=$isBuffering")
        }
    }

    /**
     * Render audio data from ring buffer.
     *
     * Key insight from Swift implementation:
     * - When buffer has enough data: read it and fill remainder with silence (jitter absorption)
     * - When buffer is empty or too low: return 0 to wait for more data
     *
     * This prevents MediaCodec from consuming faster than Unity provides.
     */
    fun render(byteBuffer: ByteBuffer): Int {
        ensureBuffer()
        val buffer = ringBuffer ?: return 0

        val start = byteBuffer.position()
        val remaining = byteBuffer.remaining()
        if (remaining <= 0) return 0

        renderCount++

        // During buffering mode, return 0 to tell MediaCodec to wait
        if (isBuffering) {
            if (bufferingStartTime == 0L) {
                bufferingStartTime = System.currentTimeMillis()
            }
            val elapsed = System.currentTimeMillis() - bufferingStartTime
            if (renderCount <= 10 || renderCount % 100 == 0) {
                Log.d(TAG, "render #$renderCount: BUFFERING elapsed=${elapsed}ms, appendCount=$appendCount, target=$minBufferFrames")
            }
            return 0
        }

        val requestedSamples = remaining / 2
        val requestedPairs = requestedSamples / channelCount
        val available = availableSamples

        // KEY FIX: If buffer is empty or too low, return 0 to wait for more data
        // This prevents MediaCodec from consuming faster than Unity provides
        // Allow some jitter absorption: only wait if we have less than half a frame
        val minRequired = SAMPLES_PER_FRAME / 2  // 512 sample pairs minimum
        if (available < minRequired) {
            waitCount++
            if (waitCount <= 10 || waitCount % 100 == 0) {
                Log.d(TAG, "render #$renderCount: WAITING avail=$available < min=$minRequired, waitCount=$waitCount")
            }
            return 0
        }

        var pairsWritten = 0

        // Read available data from ring buffer
        val pairsToRead = minOf(requestedPairs, available)
        for (i in 0 until pairsToRead) {
            val readPos = tail * channelCount
            for (ch in 0 until channelCount) {
                byteBuffer.putShort(buffer[readPos + ch])
            }
            tail = (tail + 1) % bufferCapacity
            pairsWritten++
            realSamplePairs++
        }

        // Fill remaining with silence (jitter absorption, like Swift's skip handling)
        // This only happens when we have SOME data but not enough for full frame
        val silentPairs = requestedPairs - pairsToRead
        for (i in 0 until silentPairs) {
            for (ch in 0 until channelCount) {
                byteBuffer.putShort(0)
            }
            pairsWritten++
            silentSamplePairs++
        }

        // Calculate timestamp based on samples rendered (like Swift's AudioTime)
        // Convert sample count to microseconds: samples * 1_000_000 / sampleRate
        presentationTimestamp = sampleTime * 1_000_000L / sampleRate
        sampleTime += pairsWritten

        // Log statistics
        if (renderCount <= 10 || renderCount % 100 == 0) {
            val total = realSamplePairs + silentSamplePairs
            val silentPct = if (total > 0) (silentSamplePairs * 100 / total) else 0
            Log.d(TAG, "render #$renderCount: out=$pairsWritten, avail=$available, read=$pairsToRead, silent=$silentPairs, silentPct=$silentPct%, ts=${presentationTimestamp/1000}ms")
        }

        return byteBuffer.position() - start
    }

    fun clear() {
        head = 0
        tail = 0
        sampleTime = 0
        presentationTimestamp = DEFAULT_PRESENTATION_TIMESTAMP
        appendCount = 0
        renderCount = 0
        waitCount = 0
        realSamplePairs = 0
        silentSamplePairs = 0
        isBuffering = true
        bufferingStartTime = 0L
        Log.d(TAG, "Buffer cleared")
    }

    companion object {
        private const val TAG = "AudioCodecBuffer"
        const val DEFAULT_PRESENTATION_TIMESTAMP = 0L
        const val SAMPLES_PER_FRAME = 1024
        const val BUFFER_COUNT = 16  // Like Swift's bufferCounts = 16
    }
}
