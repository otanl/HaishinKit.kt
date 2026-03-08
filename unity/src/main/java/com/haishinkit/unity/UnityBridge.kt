package com.haishinkit.unity

import android.app.Activity
import android.util.Log
import java.lang.ref.WeakReference

/**
 * Unity向けブリッジクラス
 * UnityのUnitySendMessageを使用してコールバックを送信
 */
@Suppress("unused")
object UnityBridge {
    private const val TAG = "UnityBridge"

    // デバッグログ制御
    @Volatile
    @JvmStatic
    var debugEnabled: Boolean = false
        set(value) {
            field = value
            wrapper?.debugEnabled = value
        }

    // Unity側のGameObject名とメソッド名
    private var callbackGameObject: String = "HaishinKitManager"
    private var callbackMethodName: String = "OnNativeStatusCallback"

    // アクティビティの弱参照
    private var activityRef: WeakReference<Activity>? = null

    // HaishinKitUnityWrapperのインスタンス
    private var wrapper: HaishinKitUnityWrapper? = null

    private fun debugLog(message: String) {
        if (debugEnabled) Log.d(TAG, message)
    }

    /**
     * Unity側から呼び出される初期化メソッド
     */
    @JvmStatic
    fun initialize(activity: Activity) {
        debugLog("initialize called with activity: $activity")
        try {
            activityRef = WeakReference(activity)
            wrapper = HaishinKitUnityWrapper(activity).apply {
                this.debugEnabled = this@UnityBridge.debugEnabled
                setStatusCallback { status ->
                    debugLog("Status callback: $status")
                    sendMessageToUnity(status)
                }
            }
            debugLog("initialize completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "initialize FAILED: ${e.message}", e)
        }
    }

    /**
     * コールバック先を設定
     */
    @JvmStatic
    fun setCallback(gameObject: String, methodName: String) {
        callbackGameObject = gameObject
        callbackMethodName = methodName
    }

    /**
     * Unityにメッセージを送信
     */
    private fun sendMessageToUnity(message: String) {
        try {
            val unityPlayerClass = Class.forName("com.unity3d.player.UnityPlayer")
            val sendMessageMethod = unityPlayerClass.getMethod(
                "UnitySendMessage",
                String::class.java,
                String::class.java,
                String::class.java
            )
            sendMessageMethod.invoke(null, callbackGameObject, callbackMethodName, message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message to Unity: ${e.message}")
        }
    }

    /**
     * バージョンを取得
     */
    @JvmStatic
    fun getVersion(): String {
        val version = wrapper?.getVersion() ?: "not initialized"
        debugLog("getVersion: $version")
        return version
    }

    /**
     * 接続
     */
    @JvmStatic
    fun connect(url: String, streamName: String) {
        debugLog("connect called: url=$url, streamName=$streamName")
        if (wrapper == null) {
            Log.e(TAG, "connect FAILED: wrapper is null!")
            return
        }
        try {
            wrapper?.connect(url, streamName)
        } catch (e: Exception) {
            Log.e(TAG, "connect FAILED: ${e.message}", e)
        }
    }

    /**
     * 切断
     */
    @JvmStatic
    fun disconnect() {
        wrapper?.disconnect()
    }

    /**
     * テクスチャモードで配信開始
     */
    @JvmStatic
    fun startPublishingWithTexture(width: Int, height: Int) {
        wrapper?.startPublishingWithTexture(width, height)
    }

    /**
     * 配信停止
     */
    @JvmStatic
    fun stopPublishing() {
        wrapper?.stopPublishing()
    }

    /**
     * ビデオフレーム送信
     */
    @JvmStatic
    fun sendVideoFrame(pixels: ByteArray, width: Int, height: Int) {
        debugLog("sendVideoFrame: ${pixels.size} bytes, ${width}x${height}")
        wrapper?.sendVideoFrame(pixels, width, height)
    }

    /**
     * ビデオフレーム送信（Native OpenGL Texture - Zero Copy）
     */
    @JvmStatic
    fun sendVideoFrameNativeTexture(textureId: Int, width: Int, height: Int) {
        wrapper?.sendVideoFrameNativeTexture(textureId, width, height)
    }

    /**
     * Native Texture Mode設定（Zero Copy）
     */
    @JvmStatic
    fun setUseNativeTexture(enabled: Boolean) {
        wrapper?.setUseNativeTexture(enabled)
    }

    /**
     * C++ Native Plugin Mode設定（Zero Copy via GL.IssuePluginEvent）
     */
    @JvmStatic
    fun setUseNativePlugin(enabled: Boolean) {
        wrapper?.setUseNativePlugin(enabled)
    }

    /**
     * Initialize the C++ Native Plugin with MediaCodec's input surface
     */
    @JvmStatic
    fun initializeNativePlugin(): Boolean {
        return wrapper?.initializeNativePlugin() ?: false
    }

    /**
     * Check if the C++ Native Plugin is ready to render
     */
    @JvmStatic
    fun isNativePluginReady(): Boolean {
        return wrapper?.isNativePluginReady() ?: false
    }

    /**
     * オーディオフレーム送信
     */
    @JvmStatic
    fun sendAudioFrame(samples: FloatArray, sampleCount: Int, channels: Int, sampleRate: Int) {
        wrapper?.sendAudioFrame(samples, sampleCount, channels, sampleRate)
    }

    /**
     * オーディオフレーム送信（バイト配列版）
     */
    @JvmStatic
    fun sendAudioFrameBytes(samples: ByteArray) {
        wrapper?.sendAudioFrameBytes(samples)
    }

    /**
     * 外部オーディオ使用設定
     */
    @JvmStatic
    fun setUseExternalAudio(enabled: Boolean) {
        wrapper?.setUseExternalAudio(enabled)
    }

    /**
     * ビデオビットレート設定
     */
    @JvmStatic
    fun setVideoBitrate(kbps: Int) {
        wrapper?.setVideoBitrate(kbps)
    }

    /**
     * オーディオビットレート設定
     */
    @JvmStatic
    fun setAudioBitrate(kbps: Int) {
        wrapper?.setAudioBitrate(kbps)
    }

    /**
     * オーディオサンプルレート設定
     */
    @JvmStatic
    fun setAudioSampleRate(sampleRate: Int) {
        wrapper?.setAudioSampleRate(sampleRate)
    }

    /**
     * フレームレート設定
     */
    @JvmStatic
    fun setFrameRate(fps: Int) {
        wrapper?.setFrameRate(fps)
    }

    /**
     * クリーンアップ
     */
    @JvmStatic
    fun cleanup() {
        wrapper?.cleanup()
        wrapper = null
        activityRef = null
    }
}
