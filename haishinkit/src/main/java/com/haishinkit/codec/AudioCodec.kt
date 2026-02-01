package com.haishinkit.codec

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import kotlin.properties.Delegates

/**
 * The AudioCodec translate audio data to another format.
 */
class AudioCodec : Codec() {
    @Suppress("UNUSED")
    data class Setting(
        private var codec: AudioCodec? = null,
    ) : Codec.Setting(codec) {
        /**
         * The channel of audio output.
         */
        var channelCount: Int by Delegates.observable(DEFAULT_CHANNEL_COUNT) { _, oldValue, newValue ->
            if (oldValue != newValue) {
                codec?.channelCount = newValue
            }
        }

        /**
         * The bitRate of audio output.
         */
        var bitRate: Int by Delegates.observable(DEFAULT_BIT_RATE) { _, oldValue, newValue ->
            if (oldValue != newValue) {
                codec?.bitRate = newValue
            }
        }

        /**
         * The sampleRate of audio output.
         */
        var sampleRate: Int by Delegates.observable(DEFAULT_SAMPLE_RATE) { _, oldValue, newValue ->
            if (oldValue != newValue) {
                codec?.sampleRate = newValue
            }
        }
    }

    var sampleRate = DEFAULT_SAMPLE_RATE
        set(value) {
            field = value
            buffer.sampleRate = value
        }
    var channelCount = DEFAULT_CHANNEL_COUNT
        set(value) {
            field = value
            buffer.channelCount = value
        }
    var bitRate = DEFAULT_BIT_RATE
    var aacProfile = DEFAULT_AAC_PROFILE
    override var inputMimeType = MediaFormat.MIMETYPE_AUDIO_RAW
    override var outputMimeType = MediaFormat.MIMETYPE_AUDIO_AAC
    private var buffer = AudioCodecBuffer()

    // Pending input buffer indices waiting for data
    private val pendingBufferIndices = java.util.concurrent.ConcurrentLinkedQueue<Int>()

    fun append(byteBuffer: ByteBuffer) {
        if (!isRunning.get()) return
        buffer.append(byteBuffer)

        // Process any pending buffers now that we have data
        processPendingBuffers()
    }

    private var inputBufferCount = 0

    /**
     * Process pending input buffers that were waiting for data
     */
    private fun processPendingBuffers() {
        val codec = this.codec ?: return
        while (pendingBufferIndices.isNotEmpty()) {
            val index = pendingBufferIndices.peek() ?: break
            try {
                val inputBuffer = codec.getInputBuffer(index) ?: break
                val result = buffer.render(inputBuffer)

                if (result == 0) {
                    // Still no data, keep the index pending
                    break
                }

                // Remove from pending queue
                pendingBufferIndices.poll()

                inputBufferCount++
                if (inputBufferCount <= 10 || inputBufferCount % 100 == 0) {
                    Log.d(TAG, "processPendingBuffers #$inputBufferCount: index=$index, rendered=$result, timestamp=${buffer.presentationTimestamp/1000}ms, pending=${pendingBufferIndices.size}")
                }

                codec.queueInputBuffer(
                    index,
                    0,
                    result,
                    buffer.presentationTimestamp,
                    0,
                )
            } catch (e: IllegalStateException) {
                Log.w(TAG, "processPendingBuffers error", e)
                break
            }
        }
    }

    override fun onInputBufferAvailable(
        codec: MediaCodec,
        index: Int,
    ) {
        if (mode == MODE_ENCODE) {
            try {
                val inputBuffer = codec.getInputBuffer(index) ?: return
                val bufferCapacity = inputBuffer.capacity()
                val bufferRemaining = inputBuffer.remaining()

                val result = buffer.render(inputBuffer)

                inputBufferCount++

                if (result == 0) {
                    // No data available yet, save the index for later
                    pendingBufferIndices.offer(index)
                    if (inputBufferCount <= 10 || inputBufferCount % 1000 == 0) {
                        Log.d(TAG, "onInputBufferAvailable #$inputBufferCount: WAITING for data, index=$index, pending=${pendingBufferIndices.size}")
                    }
                    return
                }

                if (inputBufferCount <= 10 || inputBufferCount % 100 == 0) {
                    Log.d(TAG, "onInputBufferAvailable #$inputBufferCount: index=$index, capacity=$bufferCapacity, remaining=$bufferRemaining, rendered=$result, timestamp=${buffer.presentationTimestamp/1000}ms, channelCount=$channelCount, sampleRate=$sampleRate")
                }

                codec.queueInputBuffer(
                    index,
                    0,
                    result,
                    buffer.presentationTimestamp,
                    0,
                )
            } catch (e: IllegalStateException) {
                Log.w(TAG, e)
            }
        } else {
            super.onInputBufferAvailable(codec, index)
        }
    }

    override fun createMediaFormat(mime: String): MediaFormat =
        MediaFormat.createAudioFormat(mime, sampleRate, channelCount).apply {
            if (mode == MODE_ENCODE) {
                setInteger(MediaFormat.KEY_AAC_PROFILE, aacProfile)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            } else {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, DEFAULT_KEY_MAX_INPUT_SIZE)
            }
        }

    override fun dispose() {
        pendingBufferIndices.clear()
        buffer.clear()
        super.dispose()
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE: Int = 44100
        const val DEFAULT_CHANNEL_COUNT: Int = 1
        const val DEFAULT_BIT_RATE: Int = 64000
        const val DEFAULT_AAC_PROFILE = MediaCodecInfo.CodecProfileLevel.AACObjectLC
        const val DEFAULT_KEY_MAX_INPUT_SIZE = 1024 * 2

        private val TAG = AudioCodec::class.java.simpleName
    }
}
