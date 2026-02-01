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

    // Unity側のGameObject名とメソッド名
    private var callbackGameObject: String = "HaishinKitManager"
    private var callbackMethodName: String = "OnNativeStatusCallback"

    // アクティビティの弱参照
    private var activityRef: WeakReference<Activity>? = null

    // HaishinKitUnityWrapperのインスタンス
    private var wrapper: HaishinKitUnityWrapper? = null

    /**
     * Unity側から呼び出される初期化メソッド
     */
    @JvmStatic
    fun initialize(activity: Activity) {
        Log.d(TAG, ">>> initialize called with activity: $activity")
        try {
            activityRef = WeakReference(activity)
            Log.d(TAG, ">>> Creating HaishinKitUnityWrapper...")
            wrapper = HaishinKitUnityWrapper(activity).apply {
                setStatusCallback { status ->
                    Log.d(TAG, ">>> Status callback: $status")
                    sendMessageToUnity(status)
                }
            }
            Log.d(TAG, ">>> initialize completed successfully, wrapper=$wrapper")
        } catch (e: Exception) {
            Log.e(TAG, ">>> initialize FAILED: ${e.message}", e)
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
            // UnityPlayerクラスをリフレクションで取得
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
        Log.d(TAG, ">>> getVersion: $version, wrapper=$wrapper")
        return version
    }

    /**
     * 接続
     */
    @JvmStatic
    fun connect(url: String, streamName: String) {
        Log.d(TAG, ">>> connect called: url=$url, streamName=$streamName, wrapper=$wrapper")
        if (wrapper == null) {
            Log.e(TAG, ">>> connect FAILED: wrapper is null!")
            return
        }
        try {
            wrapper?.connect(url, streamName)
            Log.d(TAG, ">>> connect call completed")
        } catch (e: Exception) {
            Log.e(TAG, ">>> connect FAILED: ${e.message}", e)
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
        Log.d(TAG, ">>> sendVideoFrame: ${pixels.size} bytes, ${width}x${height}")
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
     * This is the recommended approach for zero-copy texture sharing.
     */
    @JvmStatic
    fun setUseNativePlugin(enabled: Boolean) {
        wrapper?.setUseNativePlugin(enabled)
    }

    /**
     * Initialize the C++ Native Plugin with MediaCodec's input surface
     * Must be called after videoCodec is initialized.
     * @return true if initialization succeeded
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
