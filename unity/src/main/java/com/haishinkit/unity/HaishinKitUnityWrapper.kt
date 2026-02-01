package com.haishinkit.unity

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.haishinkit.media.MediaBuffer
import com.haishinkit.media.MediaType
import com.haishinkit.rtmp.RtmpConnection
import com.haishinkit.rtmp.RtmpStream
import com.haishinkit.rtmp.event.Event
import com.haishinkit.rtmp.event.EventUtils
import com.haishinkit.rtmp.event.IEventListener
import com.haishinkit.screen.ImageScreenObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unity向けHaishinKitラッパークラス
 * UnityからAndroidJavaObjectで呼び出される
 */
@Suppress("unused")
class HaishinKitUnityWrapper(private val context: Context) {

    companion object {
        private const val TAG = "HaishinKitUnity"
        const val VERSION = "1.0.0"

        // AACエンコーダーが期待するフレームサイズ
        private const val AAC_SAMPLES_PER_FRAME = 1024
    }

    // RTMP接続
    private var connection: RtmpConnection? = null
    private var stream: RtmpStream? = null

    // 外部テクスチャ用
    private var imageScreenObject: ImageScreenObject? = null
    private var isTextureMode = false
    private var videoWidth = 0
    private var videoHeight = 0

    // Direct Surface描画用（シンプルなアーキテクチャ）
    private var useDirectSurface = true  // デフォルトで有効
    private var bitmapRenderer: BitmapRenderer? = null
    private var frameCount = 0

    // 無音オーディオ生成用
    private var silentAudioEnabled = true  // YouTubeは音声が必要
    private var silentAudioThread: Thread? = null
    private var silentAudioRunning = false

    // Bitmap再利用用（ソース用のみ、flippedはImageScreenObjectに渡して管理させる）
    private var reusableBitmap: Bitmap? = null

    // 外部オーディオ用
    private var useExternalAudio = false
    private var audioSampleRate = 44100
    private var audioChannels = 2
    private var externalAudioSampleCount = 0L
    private var externalAudioStartTimeNanos = 0L
    private var externalAudioFrameCount = 0

    // オーディオバッファリング（小さいフレームを蓄積して1024サンプルにする）
    private var audioAccumulationBuffer: FloatArray? = null
    private var audioAccumulationIndex = 0

    // ストリーム名
    private var streamName: String = "live"

    // コルーチンスコープ
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ステータスコールバック用
    private var statusCallback: ((String) -> Unit)? = null

    // イベントリスナー
    private val eventListener = object : IEventListener {
        override fun handleEvent(event: Event) {
            val data = EventUtils.toMap(event)
            val code = data["code"]?.toString() ?: return

            Log.d(TAG, ">>> Event received: $code, data=$data")

            when (code) {
                RtmpConnection.Code.CONNECT_SUCCESS.rawValue -> {
                    notifyStatus("connected")
                }
                RtmpConnection.Code.CONNECT_FAILED.rawValue -> {
                    notifyStatus("error:connection failed")
                }
                RtmpConnection.Code.CONNECT_CLOSED.rawValue -> {
                    notifyStatus("disconnected")
                }
                RtmpStream.Code.PUBLISH_START.rawValue -> {
                    notifyStatus("publishing")
                    // 無音オーディオを開始
                    if (silentAudioEnabled && !useExternalAudio) {
                        startSilentAudio()
                    }
                }
                RtmpStream.Code.PUBLISH_BAD_NAME.rawValue -> {
                    notifyStatus("error:bad stream name")
                }
            }
        }
    }

    /**
     * バージョンを取得
     */
    fun getVersion(): String = VERSION

    /**
     * RTMPサーバーに接続
     */
    fun connect(url: String, streamName: String) {
        Log.d(TAG, "connect: $url, $streamName")

        scope.launch {
            try {
                // 既存の接続をクリーンアップ
                cleanup()

                // 新しい接続を作成
                connection = RtmpConnection().apply {
                    addEventListener(Event.RTMP_STATUS, eventListener)
                }

                stream = RtmpStream(context, connection!!).apply {
                    addEventListener(Event.RTMP_STATUS, eventListener)
                }

                // ストリーム名を保存
                this@HaishinKitUnityWrapper.streamName = streamName

                // 接続
                connection?.connect(url)

            } catch (e: Exception) {
                Log.e(TAG, "Connect failed", e)
                notifyStatus("error:${e.message}")
            }
        }
    }

    /**
     * 切断
     */
    fun disconnect() {
        Log.d(TAG, "disconnect")

        scope.launch {
            try {
                stream?.close()
                connection?.close()
                notifyStatus("disconnected")
            } catch (e: Exception) {
                Log.e(TAG, "Disconnect failed", e)
            }
        }
    }

    /**
     * テクスチャモードで配信開始
     */
    fun startPublishingWithTexture(width: Int, height: Int) {
        Log.d(TAG, "startPublishingWithTexture: ${width}x${height}, useDirectSurface=$useDirectSurface")

        isTextureMode = true
        videoWidth = width
        videoHeight = height
        frameCount = 0

        // BitmapRendererをリセット（後で初期化）
        bitmapRenderer?.release()
        bitmapRenderer = null

        scope.launch {
            try {
                val rtmpStream = stream ?: run {
                    notifyStatus("error:stream not initialized")
                    return@launch
                }

                // ビデオ設定
                rtmpStream.videoSetting.width = width
                rtmpStream.videoSetting.height = height
                rtmpStream.videoSetting.bitRate = 2_000_000

                // オーディオ設定
                Log.d(TAG, ">>> Setting audio: bitRate=128000, sampleRate=$audioSampleRate, channels=$audioChannels")
                rtmpStream.audioSetting.bitRate = 128_000
                rtmpStream.audioSetting.sampleRate = audioSampleRate
                rtmpStream.audioSetting.channelCount = audioChannels
                Log.d(TAG, ">>> Audio settings applied")

                if (useDirectSurface) {
                    // シンプルなアーキテクチャ：直接Surface描画
                    // useExternalVideoInputをtrueに設定して、OpenGLパイプラインをバイパス
                    Log.d(TAG, ">>> Using direct surface mode (bypassing Screen/PixelTransform)")
                    rtmpStream.useExternalVideoInput = true
                } else {
                    // 従来のアーキテクチャ：ImageScreenObject経由
                    // スクリーンサイズを設定
                    rtmpStream.screen.frame = Rect(0, 0, width, height)

                    // ImageScreenObjectを作成してスクリーンに追加
                    imageScreenObject = ImageScreenObject().apply {
                        frame = Rect(0, 0, width, height)
                    }
                    rtmpStream.screen.addChild(imageScreenObject!!)
                }

                // hasVideo/hasAudioを設定
                val enableAudio = useExternalAudio || silentAudioEnabled
                Log.d(TAG, ">>> Setting hasVideo=true, hasAudio=$enableAudio (external=$useExternalAudio, silent=$silentAudioEnabled)")
                rtmpStream.hasVideo = true
                rtmpStream.hasAudio = enableAudio

                // 配信開始
                Log.d(TAG, ">>> Calling publish(${this@HaishinKitUnityWrapper.streamName})")
                rtmpStream.publish(this@HaishinKitUnityWrapper.streamName)
                Log.d(TAG, ">>> publish() called")

            } catch (e: Exception) {
                Log.e(TAG, "startPublishingWithTexture failed", e)
                notifyStatus("error:${e.message}")
            }
        }
    }

    /**
     * 配信停止
     */
    fun stopPublishing() {
        Log.d(TAG, "stopPublishing")

        scope.launch {
            try {
                stopSilentAudio()
                if (!useDirectSurface) {
                    imageScreenObject?.let {
                        stream?.screen?.removeChild(it)
                    }
                    imageScreenObject = null
                }
                bitmapRenderer?.release()
                bitmapRenderer = null
                stream?.useExternalVideoInput = false
                isTextureMode = false
                frameCount = 0

                stream?.publish(null)
                notifyStatus("stopped")
            } catch (e: Exception) {
                Log.e(TAG, "stopPublishing failed", e)
            }
        }
    }

    /**
     * ビデオフレームを送信（RGBA byte array）
     * Unity側でRenderTexture.ReadPixelsで取得したデータを受け取る
     */
    fun sendVideoFrame(pixels: ByteArray, width: Int, height: Int) {
        if (frameCount < 5) {
            Log.d(TAG, "sendVideoFrame called: ${pixels.size} bytes, ${width}x${height}, isTextureMode=$isTextureMode, useDirectSurface=$useDirectSurface")
        }
        if (!isTextureMode) {
            Log.w(TAG, "sendVideoFrame: not in texture mode, returning")
            return
        }

        try {
            // Bitmapを再利用または作成
            if (reusableBitmap == null || reusableBitmap!!.width != width || reusableBitmap!!.height != height) {
                Log.d(TAG, "sendVideoFrame: creating new bitmap ${width}x${height}")
                reusableBitmap?.recycle()
                reusableBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }

            // byte配列からBitmapにコピー
            val buffer = ByteBuffer.wrap(pixels)
            reusableBitmap!!.copyPixelsFromBuffer(buffer)

            if (useDirectSurface) {
                // シンプルなアーキテクチャ：直接Surface描画
                if (frameCount < 5) {
                    val rtmpStream = stream
                    val inputSurface = rtmpStream?.videoCodec?.inputSurface
                    Log.d(TAG, "sendVideoFrame: useDirectSurface=true, stream=$rtmpStream, inputSurface=$inputSurface")
                }
                drawBitmapToSurface(reusableBitmap!!)
            } else {
                // 従来のアーキテクチャ：ImageScreenObject経由
                val flipped = flipBitmapVertically(reusableBitmap!!)
                imageScreenObject?.bitmap = flipped
            }

            frameCount++
            if (frameCount <= 5 || frameCount % 100 == 0) {
                Log.d(TAG, "sendVideoFrame: frame #$frameCount processed")
            }

        } catch (e: Exception) {
            Log.e(TAG, "sendVideoFrame failed", e)
        }
    }

    /**
     * BitmapをMediaCodecの入力Surfaceに直接描画（OpenGL ES使用）
     */
    private fun drawBitmapToSurface(bitmap: Bitmap) {
        val rtmpStream = stream ?: return
        val inputSurface = rtmpStream.videoCodec.inputSurface

        if (inputSurface == null) {
            if (frameCount < 10) {
                Log.w(TAG, "drawBitmapToSurface: inputSurface is null, videoCodec may not be ready")
            }
            return
        }

        if (!inputSurface.isValid) {
            if (frameCount < 10) {
                Log.w(TAG, "drawBitmapToSurface: inputSurface is not valid")
            }
            return
        }

        // BitmapRendererを初期化（初回のみ）
        if (bitmapRenderer == null) {
            Log.d(TAG, "drawBitmapToSurface: initializing BitmapRenderer")
            bitmapRenderer = BitmapRenderer()
            if (!bitmapRenderer!!.initialize(inputSurface, videoWidth, videoHeight)) {
                Log.e(TAG, "drawBitmapToSurface: BitmapRenderer initialization failed")
                bitmapRenderer = null
                return
            }
            Log.d(TAG, "drawBitmapToSurface: BitmapRenderer initialized successfully")
        }

        // OpenGL ESでBitmapを描画
        if (!bitmapRenderer!!.drawBitmap(bitmap)) {
            if (frameCount < 10) {
                Log.w(TAG, "drawBitmapToSurface: drawBitmap failed")
            }
        }
    }

    /**
     * ビデオフレームを送信（Bitmap）
     */
    fun sendVideoFrameBitmap(bitmap: Bitmap) {
        if (!isTextureMode) return

        try {
            if (useDirectSurface) {
                // シンプルなアーキテクチャ：直接Surface描画
                drawBitmapToSurface(bitmap)
            } else {
                // 従来のアーキテクチャ：ImageScreenObject経由
                val flippedBitmap = flipBitmapVertically(bitmap)
                imageScreenObject?.bitmap = flippedBitmap
            }
            frameCount++
        } catch (e: Exception) {
            Log.e(TAG, "sendVideoFrameBitmap failed", e)
        }
    }

    /**
     * オーディオフレームを送信
     * @param samples インターリーブされたFloat32 PCMサンプル（バッファプールから来るので実際のサイズより大きい可能性）
     * @param sampleCount サンプル数（チャンネルあたり）
     * @param channels チャンネル数
     * @param sampleRate サンプルレート
     */
    private var sendAudioFrameCallCount = 0

    fun sendAudioFrame(samples: FloatArray, sampleCount: Int, channels: Int, sampleRate: Int) {
        sendAudioFrameCallCount++
        if (sendAudioFrameCallCount <= 10 || sendAudioFrameCallCount % 200 == 0) {
            Log.d(TAG, ">>> sendAudioFrame #$sendAudioFrameCallCount: sampleCount=$sampleCount, channels=$channels, isTextureMode=$isTextureMode, useExternalAudio=$useExternalAudio")
        }

        if (!isTextureMode || !useExternalAudio) {
            if (sendAudioFrameCallCount <= 10) {
                Log.d(TAG, ">>> sendAudioFrame #$sendAudioFrameCallCount SKIPPED: isTextureMode=$isTextureMode, useExternalAudio=$useExternalAudio")
            }
            return
        }

        val rtmpStream = stream
        if (rtmpStream == null) {
            if (sendAudioFrameCallCount <= 10) {
                Log.d(TAG, ">>> sendAudioFrame #$sendAudioFrameCallCount SKIPPED: stream is null")
            }
            return
        }

        try {
            // 蓄積バッファを初期化（必要に応じて）
            val targetSamples = AAC_SAMPLES_PER_FRAME * channels
            if (audioAccumulationBuffer == null || audioAccumulationBuffer!!.size != targetSamples) {
                audioAccumulationBuffer = FloatArray(targetSamples)
                audioAccumulationIndex = 0
                Log.d(TAG, ">>> Audio accumulation buffer initialized: targetSamples=$targetSamples")
            }

            // 実際のサンプル数を計算
            val actualSampleCount = sampleCount * channels

            // 入力データをバッファに蓄積
            var inputIndex = 0
            while (inputIndex < actualSampleCount) {
                val toCopy = minOf(actualSampleCount - inputIndex, targetSamples - audioAccumulationIndex)
                System.arraycopy(samples, inputIndex, audioAccumulationBuffer!!, audioAccumulationIndex, toCopy)
                audioAccumulationIndex += toCopy
                inputIndex += toCopy

                // バッファが満杯になったら送信
                if (audioAccumulationIndex >= targetSamples) {
                    sendAccumulatedAudioFrame(rtmpStream, channels, sampleRate)
                    audioAccumulationIndex = 0
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "sendAudioFrame failed", e)
        }
    }

    /**
     * 蓄積されたオーディオフレームを送信（1024サンプル固定）
     * Ring buffer方式に変更したため、フレーム複製は不要。
     * AudioCodecBufferがデータ不足時は最後のフレームを繰り返す。
     */
    private fun sendAccumulatedAudioFrame(rtmpStream: RtmpStream, channels: Int, sampleRate: Int) {
        val buffer = audioAccumulationBuffer ?: return
        val totalSamples = buffer.size  // 1024 * channels
        val byteSize = totalSamples * 2

        // ByteBufferを作成してデータを書き込む
        val sendBuffer = ByteBuffer.allocateDirect(byteSize).order(ByteOrder.nativeOrder())

        for (i in 0 until totalSamples) {
            // Float32をInt16に変換（MediaCodecが期待する形式）
            val intSample = (buffer[i] * 32767).toInt().coerceIn(-32768, 32767).toShort()
            sendBuffer.putShort(intSample)
        }
        sendBuffer.flip()

        val mediaBuffer = MediaBuffer(
            type = MediaType.AUDIO,
            index = 0,
            payload = sendBuffer,
            timestamp = 0,  // AudioCodecBufferで管理
            sync = false
        )

        rtmpStream.append(mediaBuffer)

        externalAudioSampleCount += AAC_SAMPLES_PER_FRAME
        externalAudioFrameCount++

        if (externalAudioFrameCount <= 10 || externalAudioFrameCount % 100 == 0) {
            Log.d(TAG, ">>> External audio frame #$externalAudioFrameCount sent, samples=$AAC_SAMPLES_PER_FRAME, size=$byteSize")
        }
    }

    /**
     * オーディオフレームを送信（byte配列版）
     * @param samples Int16 PCMサンプル（バイト配列）
     */
    fun sendAudioFrameBytes(samples: ByteArray) {
        if (!isTextureMode || !useExternalAudio) return

        val rtmpStream = stream ?: return

        try {
            val byteBuffer = ByteBuffer.allocateDirect(samples.size)
                .order(ByteOrder.nativeOrder())
            byteBuffer.put(samples)
            byteBuffer.flip()

            // 連続的なタイムスタンプを計算
            if (externalAudioSampleCount == 0L) {
                externalAudioStartTimeNanos = System.nanoTime()
            }

            // サンプル数を計算（Int16 = 2バイト、ステレオ = 2チャンネル）
            val sampleCount = samples.size / (2 * audioChannels)
            val elapsedMicroseconds = externalAudioSampleCount * 1_000_000L / audioSampleRate
            val timestamp = elapsedMicroseconds

            val mediaBuffer = MediaBuffer(
                type = MediaType.AUDIO,
                index = 0,
                payload = byteBuffer,
                timestamp = timestamp,
                sync = false
            )

            rtmpStream.append(mediaBuffer)

            // サンプルカウントを更新
            externalAudioSampleCount += sampleCount
            externalAudioFrameCount++

        } catch (e: Exception) {
            Log.e(TAG, "sendAudioFrameBytes failed", e)
        }
    }

    /**
     * 外部オーディオの使用を設定
     */
    fun setUseExternalAudio(enabled: Boolean) {
        Log.d(TAG, "setUseExternalAudio: $enabled (was $useExternalAudio)")
        useExternalAudio = enabled
        if (enabled) {
            // 外部オーディオを使用する場合、無音スレッドを停止
            stopSilentAudio()
            // タイムスタンプをリセット
            externalAudioSampleCount = 0L
            externalAudioStartTimeNanos = 0L
            externalAudioFrameCount = 0
            // 蓄積バッファをリセット
            audioAccumulationBuffer = null
            audioAccumulationIndex = 0
        }
    }

    /**
     * オーディオサンプルレートを設定
     * Unityの実際のサンプルレート（AudioSettings.outputSampleRate）を設定する
     */
    fun setAudioSampleRate(sampleRate: Int) {
        Log.d(TAG, "setAudioSampleRate: $sampleRate (was $audioSampleRate)")
        audioSampleRate = sampleRate
        // 既存のストリームがあれば更新
        stream?.audioSetting?.sampleRate = sampleRate
    }

    /**
     * 直接Surface描画モードを設定
     * true: シンプルなアーキテクチャ（Bitmap → Surface → MediaCodec）
     * false: 従来のアーキテクチャ（Bitmap → ImageScreenObject → Screen → PixelTransform → MediaCodec）
     * デフォルトはtrue
     */
    fun setUseDirectSurface(enabled: Boolean) {
        useDirectSurface = enabled
        Log.d(TAG, "setUseDirectSurface: $enabled")
    }

    /**
     * ビデオビットレートを設定（kbps）
     */
    fun setVideoBitrate(kbps: Int) {
        stream?.videoSetting?.bitRate = kbps * 1000
    }

    /**
     * オーディオビットレートを設定（kbps）
     */
    fun setAudioBitrate(kbps: Int) {
        stream?.audioSetting?.bitRate = kbps * 1000
    }

    /**
     * フレームレートを設定
     */
    fun setFrameRate(fps: Int) {
        stream?.videoSetting?.frameRate = fps
    }

    /**
     * クリーンアップ
     */
    fun cleanup() {
        Log.d(TAG, "cleanup called")

        stopSilentAudio()
        if (!useDirectSurface) {
            imageScreenObject?.let {
                stream?.screen?.removeChild(it)
            }
        }
        imageScreenObject = null
        bitmapRenderer?.release()
        bitmapRenderer = null
        stream?.useExternalVideoInput = false
        isTextureMode = false
        frameCount = 0

        // Bitmapをリサイクル
        reusableBitmap?.recycle()
        reusableBitmap = null

        // オーディオバッファをクリア
        audioAccumulationBuffer = null
        audioAccumulationIndex = 0

        stream?.dispose()
        stream = null

        connection?.removeEventListener(Event.RTMP_STATUS, eventListener)
        connection?.close()
        connection = null
    }

    /**
     * ステータスコールバックを設定
     * Unity側でUnitySendMessageを使用してコールバックを受け取る
     */
    fun setStatusCallback(callback: (String) -> Unit) {
        statusCallback = callback
    }

    private fun notifyStatus(status: String) {
        Log.d(TAG, "Status: $status")
        statusCallback?.invoke(status)
    }

    private fun flipBitmapVertically(source: Bitmap): Bitmap {
        val matrix = android.graphics.Matrix().apply {
            postScale(1f, -1f, source.width / 2f, source.height / 2f)
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /**
     * 無音オーディオの送信を開始
     */
    private fun startSilentAudio() {
        if (silentAudioRunning) return
        silentAudioRunning = true

        Log.d(TAG, ">>> Starting silent audio thread")

        silentAudioThread = Thread {
            // ステレオ: 1024サンプル x 2チャンネル x 2バイト = 4096バイト（約23ms）
            val samplesPerBuffer = 1024
            val channels = audioChannels  // ステレオ = 2
            val bufferSize = samplesPerBuffer * channels * 2 // 16-bit PCM, stereo
            val intervalMs = 23L // 約23ms

            // 少し待機してAudioCodecが準備されるのを待つ
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                return@Thread
            }

            var audioFrameCount = 0
            while (silentAudioRunning) {
                try {
                    val rtmpStream = stream
                    if (rtmpStream != null && rtmpStream.hasAudio) {
                        // 毎回新しいバッファを作成（スレッドセーフのため）
                        val silentBuffer = ByteBuffer.allocateDirect(bufferSize)
                        silentBuffer.order(ByteOrder.nativeOrder())

                        // 無音で埋める（すべて0、ステレオなのでサンプル数 x チャンネル数）
                        for (i in 0 until samplesPerBuffer * channels) {
                            silentBuffer.putShort(0)
                        }
                        silentBuffer.flip()

                        val mediaBuffer = com.haishinkit.media.MediaBuffer(
                            type = com.haishinkit.media.MediaType.AUDIO,
                            index = 0,
                            payload = silentBuffer,
                            timestamp = System.nanoTime() / 1000,
                            sync = false
                        )

                        try {
                            rtmpStream.append(mediaBuffer)
                            audioFrameCount++
                            if (audioFrameCount <= 5 || audioFrameCount % 100 == 0) {
                                Log.d(TAG, ">>> Silent audio frame #$audioFrameCount sent")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, ">>> Silent audio append failed: ${e.message}")
                        }
                    }

                    Thread.sleep(intervalMs)
                } catch (e: InterruptedException) {
                    Log.d(TAG, ">>> Silent audio thread interrupted")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, ">>> Silent audio error", e)
                    // エラーが発生しても継続
                    try {
                        Thread.sleep(100)
                    } catch (ie: InterruptedException) {
                        break
                    }
                }
            }
            Log.d(TAG, ">>> Silent audio thread stopped")
        }
        silentAudioThread?.name = "SilentAudioThread"
        silentAudioThread?.start()
    }

    /**
     * 無音オーディオの送信を停止
     */
    private fun stopSilentAudio() {
        silentAudioRunning = false
        silentAudioThread?.interrupt()
        silentAudioThread = null
    }
}
