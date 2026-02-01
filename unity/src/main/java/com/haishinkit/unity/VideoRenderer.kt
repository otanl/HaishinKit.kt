package com.haishinkit.unity

import android.graphics.Bitmap
import android.view.Surface

/**
 * ビデオレンダラーの共通インターフェース
 * BitmapRendererとNativeTextureRendererの共通操作を定義
 */
interface VideoRenderer {
    /**
     * レンダラーの初期化状態
     */
    val isInitialized: Boolean

    /**
     * レンダラーを初期化
     * @param inputSurface MediaCodecの入力Surface
     * @param width ビデオ幅
     * @param height ビデオ高さ
     * @return 初期化成功時はtrue
     */
    fun initialize(inputSurface: Surface, width: Int, height: Int): Boolean

    /**
     * リソースを解放
     */
    fun release()
}

/**
 * Bitmapを入力として描画するレンダラー
 */
interface BitmapVideoRenderer : VideoRenderer {
    /**
     * Bitmapを描画
     * @param bitmap 描画するビットマップ
     * @return 描画成功時はtrue
     */
    fun drawBitmap(bitmap: Bitmap): Boolean
}

/**
 * OpenGLテクスチャIDを入力として描画するレンダラー
 */
interface TextureVideoRenderer : VideoRenderer {
    /**
     * テクスチャを描画
     * @param textureId OpenGLテクスチャID
     * @return 描画成功時はtrue
     */
    fun renderTexture(textureId: Int): Boolean
}
