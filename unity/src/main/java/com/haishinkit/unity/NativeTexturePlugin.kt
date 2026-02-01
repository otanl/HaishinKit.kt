package com.haishinkit.unity

import android.util.Log
import android.view.Surface

/**
 * Native Texture Plugin Bridge
 *
 * This class provides the bridge between Kotlin and the C++ native plugin
 * that enables zero-copy texture sharing between Unity and MediaCodec.
 *
 * The C++ plugin runs on Unity's render thread and has access to Unity's
 * OpenGL ES context, allowing it to directly read Unity's textures.
 */
object NativeTexturePlugin {
    private const val TAG = "NativeTexturePlugin"

    private var isLoaded = false

    init {
        try {
            System.loadLibrary("NativeTexturePlugin")
            isLoaded = true
            Log.d(TAG, "NativeTexturePlugin library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load NativeTexturePlugin library: ${e.message}")
            isLoaded = false
        }
    }

    /**
     * Check if the native library is loaded
     */
    fun isAvailable(): Boolean = isLoaded

    /**
     * Set the MediaCodec input surface
     * This must be called before rendering can begin
     */
    fun setSurface(surface: Surface?, width: Int, height: Int) {
        if (!isLoaded) {
            Log.w(TAG, "setSurface: library not loaded")
            return
        }
        Log.d(TAG, "setSurface: ${width}x${height}, surface=$surface")
        nativeSetSurface(surface, width, height)
    }

    /**
     * Set Unity's texture ID
     * This should be called each frame with the current texture ID
     */
    @JvmStatic
    fun setTextureId(textureId: Int) {
        if (!isLoaded) return
        nativeSetTextureId(textureId)
    }

    /**
     * Initialize the plugin
     */
    fun initialize() {
        if (!isLoaded) {
            Log.w(TAG, "initialize: library not loaded")
            return
        }
        Log.d(TAG, "initialize")
        nativeInitialize()
    }

    /**
     * Initialize EGL on the render thread
     * This should be called via GL.IssuePluginEvent
     */
    fun initializeEGL() {
        if (!isLoaded) return
        nativeInitializeEGL()
    }

    /**
     * Cleanup the plugin
     */
    fun cleanup() {
        if (!isLoaded) {
            Log.w(TAG, "cleanup: library not loaded")
            return
        }
        Log.d(TAG, "cleanup")
        nativeCleanup()
    }

    /**
     * Check if the plugin is ready to render
     */
    fun isReady(): Boolean {
        if (!isLoaded) return false
        return nativeIsReady()
    }

    // Native methods
    @JvmStatic
    private external fun nativeSetSurface(surface: Surface?, width: Int, height: Int)

    @JvmStatic
    private external fun nativeSetTextureId(textureId: Int)

    @JvmStatic
    private external fun nativeInitialize()

    @JvmStatic
    private external fun nativeInitializeEGL()

    @JvmStatic
    private external fun nativeCleanup()

    @JvmStatic
    private external fun nativeIsReady(): Boolean
}
