package com.haishinkit.unity

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
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

/**
 * Unity向けHaishinKitラッパークラス
 * UnityからAndroidJavaObjectで呼び出される
 */
@Suppress("unused")
class HaishinKitUnityWrapper(private val context: Context) {

    companion object {
        private const val TAG = "HaishinKitUnity"
        const val VERSION = "1.0.0"
    }

    // RTMP接続
    @Volatile
    private var connection: RtmpConnection? = null
    @Volatile
    private var stream: RtmpStream? = null

    // 外部テクスチャ用
    @Volatile
    private var imageScreenObject: ImageScreenObject? = null
    @Volatile
    private var isTextureMode = false
    private var videoWidth = 0
    private var videoHeight = 0

    // Direct Surface描画用（シンプルなアーキテクチャ）
    @Volatile
    private var useDirectSurface = true  // デフォルトで有効
    @Volatile
    private var bitmapRenderer: BitmapRenderer? = null
    private var frameCount = 0

    // Native Texture Rendering (zero-copy approach)
    @Volatile
    private var useNativeTexture = false  // Disabled by default until proven working
    @Volatile
    private var nativeTextureRenderer: NativeTextureRenderer? = null

    // C++ Native Plugin for zero-copy texture sharing
    @Volatile
    private var useNativePlugin = false  // Enabled via setUseNativePlugin()
    @Volatile
    private var nativePluginInitialized = false

    // Bitmap再利用用（ソース用のみ、flippedはImageScreenObjectに渡して管理させる）
    private var reusableBitmap: Bitmap? = null

    // オーディオエンジン
    private val audioEngine = AudioEngine { stream }

    // ストリーム名
    private var streamName: String = "live"

    // コルーチンスコープ
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ステータスコールバック用
    private var statusCallback: ((String) -> Unit)? = null

    // イベントリスナー（バックグラウンドスレッドから呼ばれる可能性があるため、メインスレッドにディスパッチ）
    private val eventListener = object : IEventListener {
        override fun handleEvent(event: Event) {
            val data = EventUtils.toMap(event)
            val code = data["code"]?.toString() ?: return

            // メインスレッドにディスパッチしてステータス通知とコルーチン操作を安全に実行
            scope.launch {
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
                        if (audioEngine.silentAudioEnabled && !audioEngine.useExternalAudio) {
                            audioEngine.startSilentAudio()
                        }
                    }
                    RtmpStream.Code.PUBLISH_BAD_NAME.rawValue -> {
                        notifyStatus("error:bad stream name")
                    }
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
                rtmpStream.audioSetting.bitRate = 128_000
                rtmpStream.audioSetting.sampleRate = audioEngine.sampleRate
                rtmpStream.audioSetting.channelCount = audioEngine.channels

                if (useDirectSurface) {
                    // 直接Surface描画モード
                    rtmpStream.useExternalVideoInput = true
                } else {
                    // ImageScreenObject経由モード
                    rtmpStream.screen.frame = Rect(0, 0, width, height)
                    imageScreenObject = ImageScreenObject().apply {
                        frame = Rect(0, 0, width, height)
                    }
                    rtmpStream.screen.addChild(imageScreenObject!!)
                }

                // hasVideo/hasAudioを設定
                val enableAudio = audioEngine.useExternalAudio || audioEngine.silentAudioEnabled
                rtmpStream.hasVideo = true
                rtmpStream.hasAudio = enableAudio

                // 配信開始
                rtmpStream.publish(this@HaishinKitUnityWrapper.streamName)

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
                audioEngine.stopSilentAudio()
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
     * ビデオフレームを送信（Native OpenGL Texture - Zero Copy）
     *
     * @param textureId Unity's OpenGL texture ID from GetNativeTexturePtr()
     * @param width Texture width
     * @param height Texture height
     *
     * Note: This requires Unity to use OpenGL ES backend (not Vulkan)
     */
    fun sendVideoFrameNativeTexture(textureId: Int, width: Int, height: Int) {
        if (frameCount < 5) {
            Log.d(TAG, "sendVideoFrameNativeTexture: textureId=$textureId, ${width}x${height}, isTextureMode=$isTextureMode")
        }

        if (!isTextureMode) {
            Log.w(TAG, "sendVideoFrameNativeTexture: not in texture mode")
            return
        }

        try {
            val rtmpStream = stream ?: return
            val inputSurface = rtmpStream.videoCodec.inputSurface

            if (inputSurface == null) {
                if (frameCount < 10) {
                    Log.w(TAG, "sendVideoFrameNativeTexture: inputSurface is null")
                }
                return
            }

            if (!inputSurface.isValid) {
                if (frameCount < 10) {
                    Log.w(TAG, "sendVideoFrameNativeTexture: inputSurface is not valid")
                }
                return
            }

            // Initialize NativeTextureRenderer if needed
            if (nativeTextureRenderer == null) {
                Log.d(TAG, "sendVideoFrameNativeTexture: initializing NativeTextureRenderer")
                nativeTextureRenderer = NativeTextureRenderer()
                if (!nativeTextureRenderer!!.initialize(inputSurface, width, height)) {
                    Log.e(TAG, "sendVideoFrameNativeTexture: NativeTextureRenderer initialization failed")
                    nativeTextureRenderer = null
                    return
                }
                Log.d(TAG, "sendVideoFrameNativeTexture: NativeTextureRenderer initialized successfully")
            }

            // Render the texture
            if (!nativeTextureRenderer!!.renderTexture(textureId)) {
                if (frameCount < 10) {
                    Log.w(TAG, "sendVideoFrameNativeTexture: renderTexture failed")
                }
            }

            frameCount++
            if (frameCount <= 5 || frameCount % 100 == 0) {
                Log.d(TAG, "sendVideoFrameNativeTexture: frame #$frameCount processed")
            }

        } catch (e: Exception) {
            Log.e(TAG, "sendVideoFrameNativeTexture failed", e)
        }
    }

    /**
     * Enable/disable native texture mode (zero-copy)
     */
    fun setUseNativeTexture(enabled: Boolean) {
        Log.d(TAG, "setUseNativeTexture: $enabled (was $useNativeTexture)")
        useNativeTexture = enabled
    }

    /**
     * Enable/disable C++ Native Plugin mode (zero-copy via GL.IssuePluginEvent)
     * This is the recommended approach for zero-copy texture sharing.
     */
    fun setUseNativePlugin(enabled: Boolean) {
        Log.d(TAG, "setUseNativePlugin: $enabled (was $useNativePlugin)")
        useNativePlugin = enabled
        if (enabled) {
            // Disable old native texture mode
            useNativeTexture = false
        }
    }

    /**
     * Initialize the C++ Native Plugin with the MediaCodec's input surface
     * This should be called after videoCodec is initialized.
     * Called from Unity's render thread initialization.
     */
    fun initializeNativePlugin(): Boolean {
        if (!useNativePlugin) {
            Log.w(TAG, "initializeNativePlugin: useNativePlugin is false")
            return false
        }

        val rtmpStream = stream
        if (rtmpStream == null) {
            Log.w(TAG, "initializeNativePlugin: stream is null")
            return false
        }

        val inputSurface = rtmpStream.videoCodec.inputSurface
        if (inputSurface == null) {
            Log.w(TAG, "initializeNativePlugin: inputSurface is null, videoCodec may not be ready")
            return false
        }

        if (!inputSurface.isValid) {
            Log.w(TAG, "initializeNativePlugin: inputSurface is not valid")
            return false
        }

        // Initialize the native plugin
        NativeTexturePlugin.initialize()

        // Pass the surface to the native plugin
        NativeTexturePlugin.setSurface(inputSurface, videoWidth, videoHeight)

        nativePluginInitialized = true
        Log.d(TAG, "initializeNativePlugin: success, surface=${inputSurface}, size=${videoWidth}x${videoHeight}")

        return true
    }

    /**
     * Check if the Native Plugin is ready to render
     */
    fun isNativePluginReady(): Boolean {
        return nativePluginInitialized && NativeTexturePlugin.isReady()
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
     * @param samples インターリーブされたFloat32 PCMサンプル
     * @param sampleCount サンプル数（チャンネルあたり）
     * @param channels チャンネル数
     * @param sampleRate サンプルレート
     */
    fun sendAudioFrame(samples: FloatArray, sampleCount: Int, channels: Int, sampleRate: Int) {
        if (!isTextureMode) return
        audioEngine.sendAudioFrame(samples, sampleCount, channels, sampleRate)
    }

    /**
     * オーディオフレームを送信（byte配列版）
     * @param samples Int16 PCMサンプル（バイト配列）
     */
    fun sendAudioFrameBytes(samples: ByteArray) {
        if (!isTextureMode) return
        audioEngine.sendAudioFrameBytes(samples)
    }

    /**
     * 外部オーディオの使用を設定
     */
    fun setUseExternalAudio(enabled: Boolean) {
        audioEngine.setUseExternalAudio(enabled)
    }

    /**
     * オーディオサンプルレートを設定
     * Unityの実際のサンプルレート（AudioSettings.outputSampleRate）を設定する
     */
    fun setAudioSampleRate(sampleRate: Int) {
        audioEngine.setSampleRate(sampleRate)
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

        // オーディオエンジンをクリーンアップ
        audioEngine.cleanup()

        if (!useDirectSurface) {
            imageScreenObject?.let {
                stream?.screen?.removeChild(it)
            }
        }
        imageScreenObject = null
        bitmapRenderer?.release()
        bitmapRenderer = null
        nativeTextureRenderer?.release()
        nativeTextureRenderer = null

        // Cleanup C++ Native Plugin
        if (nativePluginInitialized) {
            NativeTexturePlugin.cleanup()
            nativePluginInitialized = false
        }

        stream?.useExternalVideoInput = false
        isTextureMode = false
        frameCount = 0

        // Bitmapをリサイクル
        reusableBitmap?.recycle()
        reusableBitmap = null

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
}
