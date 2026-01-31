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
        Log.d(TAG, "initialize")
        activityRef = WeakReference(activity)
        wrapper = HaishinKitUnityWrapper(activity).apply {
            setStatusCallback { status ->
                sendMessageToUnity(status)
            }
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
    fun getVersion(): String = wrapper?.getVersion() ?: "not initialized"

    /**
     * 接続
     */
    @JvmStatic
    fun connect(url: String, streamName: String) {
        wrapper?.connect(url, streamName)
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
        wrapper?.sendVideoFrame(pixels, width, height)
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
