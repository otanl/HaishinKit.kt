package com.haishinkit.unity

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OpenGL ESを使用してBitmapをSurfaceに描画するレンダラー
 * MediaCodecのInputSurfaceへの直接描画に使用
 * 専用のバックグラウンドスレッドで動作してUnityのOpenGLコンテキストとの競合を防ぐ
 */
internal class BitmapRenderer {
    companion object {
        private const val TAG = "BitmapRenderer"

        // 頂点シェーダー
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        // フラグメントシェーダー
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """

        // 頂点座標（フルスクリーン四角形）
        private val VERTEX_DATA = floatArrayOf(
            -1f, -1f,  // 左下
             1f, -1f,  // 右下
            -1f,  1f,  // 左上
             1f,  1f   // 右上
        )

        // テクスチャ座標
        private val TEXTURE_DATA = floatArrayOf(
            0f, 0f,  // 左下
            1f, 0f,  // 右下
            0f, 1f,  // 左上
            1f, 1f   // 右上
        )
    }

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var config: EGLConfig? = null

    private var program = 0
    private var textureId = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var textureHandle = 0

    private var vertexBuffer: FloatBuffer? = null
    private var textureBuffer: FloatBuffer? = null

    private var width = 0
    private var height = 0
    private var isInitialized = AtomicBoolean(false)
    private var frameCount = 0
    private var startTimeNanos = 0L

    // バックグラウンドスレッド
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    fun initialize(inputSurface: Surface, width: Int, height: Int): Boolean {
        Log.d(TAG, "initialize: ${width}x${height}")
        this.width = width
        this.height = height

        // 専用のレンダリングスレッドを作成
        handlerThread = HandlerThread("BitmapRenderer").apply { start() }
        handler = Handler(handlerThread!!.looper)

        // スレッド上で初期化を実行
        val latch = CountDownLatch(1)
        var success = false

        handler?.post {
            success = initializeOnThread(inputSurface)
            latch.countDown()
        }

        try {
            latch.await()
        } catch (e: InterruptedException) {
            Log.e(TAG, "initialize: interrupted", e)
            return false
        }

        return success
    }

    private fun initializeOnThread(inputSurface: Surface): Boolean {
        Log.d(TAG, "initializeOnThread")

        try {
            // EGL初期化
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) {
                Log.e(TAG, "eglGetDisplay failed")
                return false
            }

            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                Log.e(TAG, "eglInitialize failed")
                return false
            }

            // EGLConfig選択
            val configAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0)) {
                Log.e(TAG, "eglChooseConfig failed")
                return false
            }
            config = configs[0]

            // EGLContext作成
            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
            )
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (context == EGL14.EGL_NO_CONTEXT) {
                Log.e(TAG, "eglCreateContext failed")
                return false
            }

            // EGLSurface作成（MediaCodecのInputSurfaceをラップ）
            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            surface = EGL14.eglCreateWindowSurface(display, config, inputSurface, surfaceAttribs, 0)
            if (surface == EGL14.EGL_NO_SURFACE) {
                Log.e(TAG, "eglCreateWindowSurface failed")
                return false
            }

            // コンテキストをカレントに設定
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                Log.e(TAG, "eglMakeCurrent failed")
                return false
            }

            // シェーダープログラム作成
            program = createProgram()
            if (program == 0) {
                Log.e(TAG, "createProgram failed")
                return false
            }

            // シェーダー属性取得
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
            textureHandle = GLES20.glGetUniformLocation(program, "uTexture")

            // テクスチャ作成
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            // 頂点バッファ作成
            vertexBuffer = ByteBuffer.allocateDirect(VERTEX_DATA.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(VERTEX_DATA)
            vertexBuffer?.position(0)

            textureBuffer = ByteBuffer.allocateDirect(TEXTURE_DATA.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(TEXTURE_DATA)
            textureBuffer?.position(0)

            GLES20.glViewport(0, 0, width, height)

            isInitialized.set(true)
            Log.d(TAG, "initializeOnThread: success, textureId=$textureId")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "initializeOnThread failed", e)
            return false
        }
    }

    fun drawBitmap(bitmap: Bitmap): Boolean {
        if (!isInitialized.get()) {
            return false
        }

        val handler = this.handler ?: return false

        // Bitmapをコピー（元のBitmapが再利用される可能性があるため）
        val bitmapCopy = bitmap.copy(Bitmap.Config.ARGB_8888, false)

        // バックグラウンドスレッドで描画
        handler.post {
            drawBitmapOnThread(bitmapCopy)
            bitmapCopy.recycle()
        }

        return true
    }

    private fun drawBitmapOnThread(bitmap: Bitmap) {
        if (!isInitialized.get()) {
            return
        }

        frameCount++

        try {
            // コンテキストをカレントに設定
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                if (frameCount <= 5) {
                    Log.e(TAG, "drawBitmapOnThread: eglMakeCurrent failed")
                }
                return
            }

            // クリア
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            // シェーダープログラム使用
            GLES20.glUseProgram(program)

            // Bitmapをテクスチャにアップロード
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)

            // 頂点属性設定
            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

            GLES20.glEnableVertexAttribArray(texCoordHandle)
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, textureBuffer)

            // テクスチャユニット設定
            GLES20.glUniform1i(textureHandle, 0)

            // 描画
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            // 属性無効化
            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(texCoordHandle)

            // タイムスタンプを設定（MediaCodecが正しいPTSを取得するために必要）
            if (startTimeNanos == 0L) {
                startTimeNanos = System.nanoTime()
            }
            val presentationTimeNanos = System.nanoTime() - startTimeNanos
            EGLExt.eglPresentationTimeANDROID(display, surface, presentationTimeNanos)

            // スワップバッファ（MediaCodecにフレームを送信）
            EGL14.eglSwapBuffers(display, surface)

            if (frameCount <= 5 || frameCount % 100 == 0) {
                Log.d(TAG, "drawBitmapOnThread: frame #$frameCount rendered and swapped, pts=${presentationTimeNanos/1000000}ms")
            }

        } catch (e: Exception) {
            Log.e(TAG, "drawBitmapOnThread failed", e)
        }
    }

    fun release() {
        Log.d(TAG, "release")
        isInitialized.set(false)
        frameCount = 0
        startTimeNanos = 0L

        // バックグラウンドスレッドで解放処理を実行
        val latch = CountDownLatch(1)
        handler?.post {
            releaseOnThread()
            latch.countDown()
        }

        try {
            latch.await()
        } catch (e: InterruptedException) {
            Log.e(TAG, "release: interrupted", e)
        }

        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
    }

    private fun releaseOnThread() {
        Log.d(TAG, "releaseOnThread")

        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }

        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }

        if (surface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(display, surface)
            surface = EGL14.EGL_NO_SURFACE
        }

        if (context != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(display, context)
            context = EGL14.EGL_NO_CONTEXT
        }

        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglTerminate(display)
            display = EGL14.EGL_NO_DISPLAY
        }

        vertexBuffer = null
        textureBuffer = null
    }

    private fun createProgram(): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        if (vertexShader == 0) return 0

        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        if (fragmentShader == 0) return 0

        val program = GLES20.glCreateProgram()
        if (program == 0) return 0

        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val error = GLES20.glGetProgramInfoLog(program)
            Log.e(TAG, "Program link failed: $error")
            GLES20.glDeleteProgram(program)
            return 0
        }

        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)

        return program
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) return 0

        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val error = GLES20.glGetShaderInfoLog(shader)
            Log.e(TAG, "Shader compile failed: $error")
            GLES20.glDeleteShader(shader)
            return 0
        }

        return shader
    }
}
