package com.haishinkit.unity

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
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
 * OpenGL ES Native Texture Renderer
 *
 * Renders Unity's native OpenGL texture directly to MediaCodec's InputSurface
 * without CPU memory copies (zero-copy approach).
 *
 * Technical approach:
 * 1. Receive Unity's OpenGL texture ID (from GetNativeTexturePtr())
 * 2. Use the texture directly in our EGL context (requires shared context or EGLImage)
 * 3. Render to MediaCodec's InputSurface
 *
 * Note: This requires Unity to use OpenGL ES backend (not Vulkan)
 */
internal class NativeTextureRenderer : TextureVideoRenderer {
    companion object {
        private const val TAG = "NativeTextureRenderer"

        // Vertex shader - simple passthrough
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        // Fragment shader for regular GL_TEXTURE_2D with gamma correction
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                vec4 color = texture2D(uTexture, vTexCoord);
                // Linear to sRGB gamma correction (gamma 2.2 approximation)
                // Using max() to ensure non-negative values for pow()
                vec3 clamped = max(color.rgb, vec3(0.0));
                vec3 srgb = pow(clamped, vec3(0.4545));
                gl_FragColor = vec4(srgb, color.a);
            }
        """

        // Vertex coordinates (fullscreen quad)
        private val VERTEX_DATA = floatArrayOf(
            -1f, -1f,  // bottom-left
             1f, -1f,  // bottom-right
            -1f,  1f,  // top-left
             1f,  1f   // top-right
        )

        // Texture coordinates (flip Y for Unity)
        private val TEXTURE_DATA = floatArrayOf(
            0f, 1f,  // bottom-left (flipped)
            1f, 1f,  // bottom-right (flipped)
            0f, 0f,  // top-left (flipped)
            1f, 0f   // top-right (flipped)
        )
    }

    // EGL resources
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var config: EGLConfig? = null

    // OpenGL resources
    private var program = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var textureHandle = 0

    private var vertexBuffer: FloatBuffer? = null
    private var textureBuffer: FloatBuffer? = null

    // State
    private var width = 0
    private var height = 0
    private var _isInitialized = AtomicBoolean(false)
    override val isInitialized: Boolean get() = _isInitialized.get()
    private var frameCount = 0
    private var startTimeNanos = 0L

    // Background thread
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    // Last texture ID for validation
    private var lastTextureId = 0

    /**
     * Initialize the renderer with MediaCodec's input surface
     */
    override fun initialize(inputSurface: Surface, width: Int, height: Int): Boolean {
        Log.d(TAG, "initialize: ${width}x${height}")
        this.width = width
        this.height = height

        // Create dedicated rendering thread
        handlerThread = HandlerThread("NativeTextureRenderer").apply { start() }
        handler = Handler(handlerThread!!.looper)

        // Initialize on thread
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
            // EGL initialization
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

            // EGLConfig selection
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

            // EGLContext creation
            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
            )
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (context == EGL14.EGL_NO_CONTEXT) {
                Log.e(TAG, "eglCreateContext failed")
                return false
            }

            // EGLSurface creation (wrapping MediaCodec's InputSurface)
            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            surface = EGL14.eglCreateWindowSurface(display, config, inputSurface, surfaceAttribs, 0)
            if (surface == EGL14.EGL_NO_SURFACE) {
                Log.e(TAG, "eglCreateWindowSurface failed")
                return false
            }

            // Make context current
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                Log.e(TAG, "eglMakeCurrent failed")
                return false
            }

            // Create shader program
            program = createProgram()
            if (program == 0) {
                Log.e(TAG, "createProgram failed")
                return false
            }

            // Get shader attribute locations
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
            textureHandle = GLES20.glGetUniformLocation(program, "uTexture")

            // Create vertex buffers
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

            _isInitialized.set(true)
            Log.d(TAG, "initializeOnThread: success")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "initializeOnThread failed", e)
            return false
        }
    }

    /**
     * Render Unity's native texture to the output surface.
     *
     * @param textureId Unity's OpenGL texture ID (from GetNativeTexturePtr())
     * @return true if rendering succeeded
     *
     * IMPORTANT: This approach has limitations:
     * - The texture ID is from Unity's EGL context, not ours
     * - Direct usage may not work across different EGL contexts
     * - If this fails, we need to use EGLImage or a native plugin approach
     */
    override fun renderTexture(textureId: Int): Boolean {
        if (!_isInitialized.get()) {
            Log.w(TAG, "renderTexture: not initialized")
            return false
        }

        if (textureId <= 0) {
            Log.w(TAG, "renderTexture: invalid textureId=$textureId")
            return false
        }

        val handler = this.handler ?: return false

        // Post to rendering thread
        handler.post {
            renderTextureOnThread(textureId)
        }

        return true
    }

    private fun renderTextureOnThread(textureId: Int) {
        if (!_isInitialized.get()) {
            return
        }

        frameCount++

        try {
            // Make our context current
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                if (frameCount <= 5) {
                    Log.e(TAG, "renderTextureOnThread: eglMakeCurrent failed")
                }
                return
            }

            // Clear
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            // Use shader program
            GLES20.glUseProgram(program)

            // Bind Unity's texture
            // NOTE: This may fail if the texture is from a different EGL context
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)

            // Check for GL errors
            val error = GLES20.glGetError()
            if (error != GLES20.GL_NO_ERROR) {
                if (frameCount <= 5 || frameCount % 100 == 0) {
                    Log.e(TAG, "renderTextureOnThread: glBindTexture error=$error for textureId=$textureId")
                    Log.e(TAG, "This likely means the texture is from a different EGL context and cannot be shared directly.")
                }
                // Continue anyway to see what happens
            }

            // Set texture parameters (in case they're not set)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            // Setup vertex attributes
            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

            GLES20.glEnableVertexAttribArray(texCoordHandle)
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, textureBuffer)

            // Set texture uniform
            GLES20.glUniform1i(textureHandle, 0)

            // Draw
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            // Disable attributes
            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(texCoordHandle)

            // Set presentation timestamp
            if (startTimeNanos == 0L) {
                startTimeNanos = System.nanoTime()
            }
            val presentationTimeNanos = System.nanoTime() - startTimeNanos
            EGLExt.eglPresentationTimeANDROID(display, surface, presentationTimeNanos)

            // Swap buffers (send frame to MediaCodec)
            EGL14.eglSwapBuffers(display, surface)

            if (frameCount <= 5 || frameCount % 100 == 0) {
                Log.d(TAG, "renderTextureOnThread: frame #$frameCount, textureId=$textureId, pts=${presentationTimeNanos/1000000}ms")
            }

            lastTextureId = textureId

        } catch (e: Exception) {
            Log.e(TAG, "renderTextureOnThread failed", e)
        }
    }

    /**
     * Release all resources
     */
    override fun release() {
        Log.d(TAG, "release")
        _isInitialized.set(false)
        frameCount = 0
        startTimeNanos = 0L

        // Release on thread
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
