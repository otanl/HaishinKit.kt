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

    // デバッグログ制御
    @Volatile
    var debugEnabled: Boolean = false

    private fun debugLog(message: String) {
        if (debugEnabled) Log.d(TAG, message)
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
    private var useNativeTexture = false
    @Volatile
    private var nativeTextureRenderer: NativeTextureRenderer? = null

    // C++ Native Plugin for zero-copy texture sharing
    @Volatile
    private var useNativePlugin = false
    @Volatile
    private var nativePluginInitialized = false

    // Bitmap再利用用
    private var reusableBitmap: Bitmap? = null

    // オーディオエンジン
    private val audioEngine = AudioEngine { stream }

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
        debugLog("connect: $url, $streamName")

        scope.launch {
            try {
                cleanup()

                connection = RtmpConnection().apply {
                    addEventListener(Event.RTMP_STATUS, eventListener)
                }

                stream = RtmpStream(context, connection!!).apply {
                    addEventListener(Event.RTMP_STATUS, eventListener)
                }

                this@HaishinKitUnityWrapper.streamName = streamName
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
        debugLog("disconnect")

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
        debugLog("startPublishingWithTexture: ${width}x${height}, useDirectSurface=$useDirectSurface")

        isTextureMode = true
        videoWidth = width
        videoHeight = height
        frameCount = 0

        bitmapRenderer?.release()
        bitmapRenderer = null

        scope.launch {
            try {
                val rtmpStream = stream ?: run {
                    notifyStatus("error:stream not initialized")
                    return@launch
                }

                rtmpStream.videoSetting.width = width
                rtmpStream.videoSetting.height = height
                rtmpStream.videoSetting.bitRate = 2_000_000

                rtmpStream.audioSetting.bitRate = 128_000
                rtmpStream.audioSetting.sampleRate = audioEngine.sampleRate
                rtmpStream.audioSetting.channelCount = audioEngine.channels

                if (useDirectSurface) {
                    rtmpStream.useExternalVideoInput = true
                } else {
                    rtmpStream.screen.frame = Rect(0, 0, width, height)
                    imageScreenObject = ImageScreenObject().apply {
                        frame = Rect(0, 0, width, height)
                    }
                    rtmpStream.screen.addChild(imageScreenObject!!)
                }

                val enableAudio = audioEngine.useExternalAudio || audioEngine.silentAudioEnabled
                rtmpStream.hasVideo = true
                rtmpStream.hasAudio = enableAudio

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
        debugLog("stopPublishing")

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
     */
    fun sendVideoFrame(pixels: ByteArray, width: Int, height: Int) {
        if (frameCount < 5) {
            debugLog("sendVideoFrame: ${pixels.size} bytes, ${width}x${height}")
        }
        if (!isTextureMode) return

        try {
            if (reusableBitmap == null || reusableBitmap!!.width != width || reusableBitmap!!.height != height) {
                debugLog("sendVideoFrame: creating new bitmap ${width}x${height}")
                reusableBitmap?.recycle()
                reusableBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }

            val buffer = ByteBuffer.wrap(pixels)
            reusableBitmap!!.copyPixelsFromBuffer(buffer)

            if (useDirectSurface) {
                if (frameCount < 5) {
                    debugLog("sendVideoFrame: useDirectSurface=true, stream=$stream, inputSurface=${stream?.videoCodec?.inputSurface}")
                }
                drawBitmapToSurface(reusableBitmap!!)
            } else {
                val flipped = flipBitmapVertically(reusableBitmap!!)
                imageScreenObject?.bitmap = flipped
            }

            frameCount++
            if (frameCount <= 5 || frameCount % 100 == 0) {
                debugLog("sendVideoFrame: frame #$frameCount processed")
            }

        } catch (e: Exception) {
            Log.e(TAG, "sendVideoFrame failed", e)
        }
    }

    /**
     * BitmapをMediaCodecの入力Surfaceに直接描画
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

        if (bitmapRenderer == null) {
            debugLog("drawBitmapToSurface: initializing BitmapRenderer")
            bitmapRenderer = BitmapRenderer()
            if (!bitmapRenderer!!.initialize(inputSurface, videoWidth, videoHeight)) {
                Log.e(TAG, "drawBitmapToSurface: BitmapRenderer initialization failed")
                bitmapRenderer = null
                return
            }
            debugLog("drawBitmapToSurface: BitmapRenderer initialized successfully")
        }

        if (!bitmapRenderer!!.drawBitmap(bitmap)) {
            if (frameCount < 10) {
                Log.w(TAG, "drawBitmapToSurface: drawBitmap failed")
            }
        }
    }

    /**
     * ビデオフレームを送信（Native OpenGL Texture - Zero Copy）
     */
    fun sendVideoFrameNativeTexture(textureId: Int, width: Int, height: Int) {
        if (frameCount < 5) {
            debugLog("sendVideoFrameNativeTexture: textureId=$textureId, ${width}x${height}")
        }

        if (!isTextureMode) return

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

            if (nativeTextureRenderer == null) {
                debugLog("sendVideoFrameNativeTexture: initializing NativeTextureRenderer")
                nativeTextureRenderer = NativeTextureRenderer()
                if (!nativeTextureRenderer!!.initialize(inputSurface, width, height)) {
                    Log.e(TAG, "sendVideoFrameNativeTexture: NativeTextureRenderer initialization failed")
                    nativeTextureRenderer = null
                    return
                }
                debugLog("sendVideoFrameNativeTexture: NativeTextureRenderer initialized successfully")
            }

            if (!nativeTextureRenderer!!.renderTexture(textureId)) {
                if (frameCount < 10) {
                    Log.w(TAG, "sendVideoFrameNativeTexture: renderTexture failed")
                }
            }

            frameCount++
            if (frameCount <= 5 || frameCount % 100 == 0) {
                debugLog("sendVideoFrameNativeTexture: frame #$frameCount processed")
            }

        } catch (e: Exception) {
            Log.e(TAG, "sendVideoFrameNativeTexture failed", e)
        }
    }

    /**
     * Enable/disable native texture mode (zero-copy)
     */
    fun setUseNativeTexture(enabled: Boolean) {
        debugLog("setUseNativeTexture: $enabled (was $useNativeTexture)")
        useNativeTexture = enabled
    }

    /**
     * Enable/disable C++ Native Plugin mode (zero-copy via GL.IssuePluginEvent)
     */
    fun setUseNativePlugin(enabled: Boolean) {
        debugLog("setUseNativePlugin: $enabled (was $useNativePlugin)")
        useNativePlugin = enabled
        if (enabled) {
            useNativeTexture = false
        }
    }

    /**
     * Initialize the C++ Native Plugin with the MediaCodec's input surface
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
            Log.w(TAG, "initializeNativePlugin: inputSurface is null")
            return false
        }

        if (!inputSurface.isValid) {
            Log.w(TAG, "initializeNativePlugin: inputSurface is not valid")
            return false
        }

        NativeTexturePlugin.initialize()
        NativeTexturePlugin.setSurface(inputSurface, videoWidth, videoHeight)

        nativePluginInitialized = true
        debugLog("initializeNativePlugin: success, size=${videoWidth}x${videoHeight}")

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
                drawBitmapToSurface(bitmap)
            } else {
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
     */
    fun sendAudioFrame(samples: FloatArray, sampleCount: Int, channels: Int, sampleRate: Int) {
        if (!isTextureMode) return
        audioEngine.sendAudioFrame(samples, sampleCount, channels, sampleRate)
    }

    /**
     * オーディオフレームを送信（byte配列版）
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
     */
    fun setAudioSampleRate(sampleRate: Int) {
        audioEngine.setSampleRate(sampleRate)
    }

    /**
     * 直接Surface描画モードを設定
     */
    fun setUseDirectSurface(enabled: Boolean) {
        useDirectSurface = enabled
        debugLog("setUseDirectSurface: $enabled")
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
        debugLog("cleanup called")

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

        if (nativePluginInitialized) {
            NativeTexturePlugin.cleanup()
            nativePluginInitialized = false
        }

        stream?.useExternalVideoInput = false
        isTextureMode = false
        frameCount = 0

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
     */
    fun setStatusCallback(callback: (String) -> Unit) {
        statusCallback = callback
    }

    private fun notifyStatus(status: String) {
        debugLog("Status: $status")
        statusCallback?.invoke(status)
    }

    private fun flipBitmapVertically(source: Bitmap): Bitmap {
        val matrix = android.graphics.Matrix().apply {
            postScale(1f, -1f, source.width / 2f, source.height / 2f)
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }
}
