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
    }

    // RTMP接続
    private var connection: RtmpConnection? = null
    private var stream: RtmpStream? = null

    // 外部テクスチャ用
    private var imageScreenObject: ImageScreenObject? = null
    private var isTextureMode = false
    private var videoWidth = 0
    private var videoHeight = 0

    // 外部オーディオ用
    private var useExternalAudio = false
    private var audioSampleRate = 44100
    private var audioChannels = 2

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

            Log.d(TAG, "Event: $code")

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
        Log.d(TAG, "startPublishingWithTexture: ${width}x${height}")

        isTextureMode = true
        videoWidth = width
        videoHeight = height

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
                rtmpStream.audioSetting.sampleRate = audioSampleRate
                rtmpStream.audioSetting.channelCount = audioChannels

                // スクリーンサイズを設定
                rtmpStream.screen.frame = Rect(0, 0, width, height)

                // ImageScreenObjectを作成してスクリーンに追加
                imageScreenObject = ImageScreenObject().apply {
                    frame = Rect(0, 0, width, height)
                }
                rtmpStream.screen.addChild(imageScreenObject!!)

                // hasVideo/hasAudioを設定
                rtmpStream.hasVideo = true
                rtmpStream.hasAudio = true

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
                imageScreenObject?.let {
                    stream?.screen?.removeChild(it)
                }
                imageScreenObject = null
                isTextureMode = false

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
        if (!isTextureMode) return

        try {
            // byte配列からBitmapを作成
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))

            // 上下反転（Unityは左下原点、Androidは左上原点）
            val flippedBitmap = flipBitmapVertically(bitmap)
            bitmap.recycle()

            // ImageScreenObjectに設定
            imageScreenObject?.bitmap = flippedBitmap

        } catch (e: Exception) {
            Log.e(TAG, "sendVideoFrame failed", e)
        }
    }

    /**
     * ビデオフレームを送信（Bitmap）
     */
    fun sendVideoFrameBitmap(bitmap: Bitmap) {
        if (!isTextureMode) return

        try {
            // 上下反転
            val flippedBitmap = flipBitmapVertically(bitmap)

            // ImageScreenObjectに設定
            imageScreenObject?.bitmap = flippedBitmap

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
        if (!isTextureMode || !useExternalAudio) return

        val rtmpStream = stream ?: return

        try {
            // Float配列をByteBufferに変換（Int16 PCM形式）
            val byteBuffer = ByteBuffer.allocateDirect(samples.size * 2)
                .order(ByteOrder.nativeOrder())

            for (sample in samples) {
                // Float32をInt16に変換（MediaCodecが期待する形式）
                val intSample = (sample * 32767).toInt().coerceIn(-32768, 32767).toShort()
                byteBuffer.putShort(intSample)
            }
            byteBuffer.flip()

            // MediaBufferを作成してStreamに追加
            val mediaBuffer = MediaBuffer(
                type = MediaType.AUDIO,
                index = 0,
                payload = byteBuffer,
                timestamp = System.nanoTime() / 1000, // マイクロ秒
                sync = false
            )

            rtmpStream.append(mediaBuffer)

        } catch (e: Exception) {
            Log.e(TAG, "sendAudioFrame failed", e)
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

            val mediaBuffer = MediaBuffer(
                type = MediaType.AUDIO,
                index = 0,
                payload = byteBuffer,
                timestamp = System.nanoTime() / 1000,
                sync = false
            )

            rtmpStream.append(mediaBuffer)

        } catch (e: Exception) {
            Log.e(TAG, "sendAudioFrameBytes failed", e)
        }
    }

    /**
     * 外部オーディオの使用を設定
     */
    fun setUseExternalAudio(enabled: Boolean) {
        useExternalAudio = enabled
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
        Log.d(TAG, "cleanup")

        imageScreenObject?.let {
            stream?.screen?.removeChild(it)
            it.bitmap?.recycle()
        }
        imageScreenObject = null
        isTextureMode = false

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
