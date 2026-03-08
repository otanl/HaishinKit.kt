package com.haishinkit.unity

import android.util.Log
import com.haishinkit.media.MediaBuffer
import com.haishinkit.media.MediaType
import com.haishinkit.rtmp.RtmpStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * オーディオ処理エンジン
 * 無音オーディオ生成と外部オーディオ処理を担当
 */
class AudioEngine(
    private val streamProvider: () -> RtmpStream?
) {
    companion object {
        private const val TAG = "AudioEngine"
        private const val AAC_SAMPLES_PER_FRAME = 1024
        private const val DEFAULT_SAMPLE_RATE = 44100
        private const val DEFAULT_CHANNELS = 2
        private const val SILENT_AUDIO_INTERVAL_MS = 23L
    }

    // デバッグログ制御
    @Volatile
    var debugEnabled: Boolean = false

    private fun debugLog(message: String) {
        if (debugEnabled) Log.d(TAG, message)
    }

    // 設定
    @Volatile
    var sampleRate: Int = DEFAULT_SAMPLE_RATE
        private set

    @Volatile
    var channels: Int = DEFAULT_CHANNELS
        private set

    @Volatile
    var useExternalAudio: Boolean = false
        private set

    @Volatile
    var silentAudioEnabled: Boolean = true

    // 無音オーディオスレッド
    @Volatile
    private var silentAudioThread: Thread? = null
    private val silentAudioRunning = AtomicBoolean(false)

    // 外部オーディオ用
    private var externalAudioSampleCount = 0L
    private var externalAudioStartTimeNanos = 0L
    private var externalAudioFrameCount = 0

    // オーディオバッファリング（小さいフレームを蓄積して1024サンプルにする）
    private var audioAccumulationBuffer: FloatArray? = null
    private var audioAccumulationIndex = 0

    // デバッグ用
    private var sendAudioFrameCallCount = 0

    /**
     * サンプルレートを設定
     */
    fun setSampleRate(rate: Int) {
        debugLog("setSampleRate: $rate (was $sampleRate)")
        sampleRate = rate
        streamProvider()?.audioSetting?.sampleRate = rate
    }

    /**
     * 外部オーディオの使用を設定
     */
    fun setUseExternalAudio(enabled: Boolean) {
        debugLog("setUseExternalAudio: $enabled (was $useExternalAudio)")
        useExternalAudio = enabled
        if (enabled) {
            stopSilentAudio()
            resetExternalAudioState()
        }
    }

    /**
     * 外部オーディオ状態をリセット
     */
    private fun resetExternalAudioState() {
        externalAudioSampleCount = 0L
        externalAudioStartTimeNanos = 0L
        externalAudioFrameCount = 0
        audioAccumulationBuffer = null
        audioAccumulationIndex = 0
        sendAudioFrameCallCount = 0
    }

    /**
     * 無音オーディオの送信を開始
     */
    fun startSilentAudio() {
        if (!silentAudioRunning.compareAndSet(false, true)) {
            return
        }

        debugLog("Starting silent audio thread")

        silentAudioThread = Thread {
            val samplesPerBuffer = AAC_SAMPLES_PER_FRAME
            val bufferSize = samplesPerBuffer * channels * 2

            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                silentAudioRunning.set(false)
                return@Thread
            }

            var audioFrameCount = 0
            while (silentAudioRunning.get()) {
                try {
                    val rtmpStream = streamProvider()
                    if (rtmpStream != null && rtmpStream.hasAudio) {
                        val silentBuffer = ByteBuffer.allocateDirect(bufferSize)
                            .order(ByteOrder.nativeOrder())

                        repeat(samplesPerBuffer * channels) {
                            silentBuffer.putShort(0)
                        }
                        silentBuffer.flip()

                        val mediaBuffer = MediaBuffer(
                            type = MediaType.AUDIO,
                            index = 0,
                            payload = silentBuffer,
                            timestamp = System.nanoTime() / 1000,
                            sync = false
                        )

                        try {
                            rtmpStream.append(mediaBuffer)
                            audioFrameCount++
                            if (audioFrameCount <= 5 || audioFrameCount % 100 == 0) {
                                debugLog("Silent audio frame #$audioFrameCount sent")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Silent audio append failed: ${e.message}")
                        }
                    }

                    Thread.sleep(SILENT_AUDIO_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    debugLog("Silent audio thread interrupted")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Silent audio error", e)
                    try {
                        Thread.sleep(100)
                    } catch (ie: InterruptedException) {
                        break
                    }
                }
            }
            silentAudioRunning.set(false)
            debugLog("Silent audio thread stopped")
        }.apply {
            name = "SilentAudioThread"
            start()
        }
    }

    /**
     * 無音オーディオの送信を停止
     */
    fun stopSilentAudio() {
        silentAudioRunning.set(false)
        silentAudioThread?.interrupt()
        silentAudioThread = null
    }

    /**
     * オーディオフレームを送信（Float配列版）
     */
    fun sendAudioFrame(samples: FloatArray, sampleCount: Int, channels: Int, sampleRate: Int): Boolean {
        sendAudioFrameCallCount++
        if (sendAudioFrameCallCount <= 10 || sendAudioFrameCallCount % 200 == 0) {
            debugLog("sendAudioFrame #$sendAudioFrameCallCount: sampleCount=$sampleCount, channels=$channels")
        }

        if (!useExternalAudio) {
            return false
        }

        val rtmpStream = streamProvider() ?: return false

        try {
            val targetSamples = AAC_SAMPLES_PER_FRAME * channels
            if (audioAccumulationBuffer == null || audioAccumulationBuffer!!.size != targetSamples) {
                audioAccumulationBuffer = FloatArray(targetSamples)
                audioAccumulationIndex = 0
                debugLog("Audio accumulation buffer initialized: targetSamples=$targetSamples")
            }

            val actualSampleCount = sampleCount * channels
            var inputIndex = 0

            while (inputIndex < actualSampleCount) {
                val toCopy = minOf(actualSampleCount - inputIndex, targetSamples - audioAccumulationIndex)
                System.arraycopy(samples, inputIndex, audioAccumulationBuffer!!, audioAccumulationIndex, toCopy)
                audioAccumulationIndex += toCopy
                inputIndex += toCopy

                if (audioAccumulationIndex >= targetSamples) {
                    sendAccumulatedAudioFrame(rtmpStream, channels, sampleRate)
                    audioAccumulationIndex = 0
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "sendAudioFrame failed", e)
            return false
        }
    }

    /**
     * 蓄積されたオーディオフレームを送信
     */
    private fun sendAccumulatedAudioFrame(rtmpStream: RtmpStream, channels: Int, sampleRate: Int) {
        val buffer = audioAccumulationBuffer ?: return
        val totalSamples = buffer.size
        val byteSize = totalSamples * 2

        val sendBuffer = ByteBuffer.allocateDirect(byteSize).order(ByteOrder.nativeOrder())

        for (i in 0 until totalSamples) {
            val intSample = (buffer[i] * 32767).toInt().coerceIn(-32768, 32767).toShort()
            sendBuffer.putShort(intSample)
        }
        sendBuffer.flip()

        val elapsedMicroseconds = externalAudioSampleCount * 1_000_000L / sampleRate

        val mediaBuffer = MediaBuffer(
            type = MediaType.AUDIO,
            index = 0,
            payload = sendBuffer,
            timestamp = elapsedMicroseconds,
            sync = false
        )

        rtmpStream.append(mediaBuffer)

        externalAudioSampleCount += AAC_SAMPLES_PER_FRAME
        externalAudioFrameCount++

        if (externalAudioFrameCount <= 10 || externalAudioFrameCount % 100 == 0) {
            debugLog("External audio frame #$externalAudioFrameCount sent")
        }
    }

    /**
     * オーディオフレームを送信（バイト配列版）
     */
    fun sendAudioFrameBytes(samples: ByteArray): Boolean {
        if (!useExternalAudio) return false

        val rtmpStream = streamProvider() ?: return false

        try {
            val byteBuffer = ByteBuffer.allocateDirect(samples.size)
                .order(ByteOrder.nativeOrder())
            byteBuffer.put(samples)
            byteBuffer.flip()

            if (externalAudioSampleCount == 0L) {
                externalAudioStartTimeNanos = System.nanoTime()
            }

            val sampleCount = samples.size / (2 * channels)
            val elapsedMicroseconds = externalAudioSampleCount * 1_000_000L / sampleRate

            val mediaBuffer = MediaBuffer(
                type = MediaType.AUDIO,
                index = 0,
                payload = byteBuffer,
                timestamp = elapsedMicroseconds,
                sync = false
            )

            rtmpStream.append(mediaBuffer)

            externalAudioSampleCount += sampleCount
            externalAudioFrameCount++
            return true
        } catch (e: Exception) {
            Log.e(TAG, "sendAudioFrameBytes failed", e)
            return false
        }
    }

    /**
     * クリーンアップ
     */
    fun cleanup() {
        stopSilentAudio()
        resetExternalAudioState()
    }
}
