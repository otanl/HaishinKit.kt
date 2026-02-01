package com.haishinkit.stream

import android.content.Context
import android.util.Log
import androidx.core.util.Pools
import com.haishinkit.BuildConfig
import com.haishinkit.codec.AudioCodec
import com.haishinkit.codec.Codec
import com.haishinkit.codec.VideoCodec
import com.haishinkit.media.MediaBuffer
import com.haishinkit.media.MediaLink
import com.haishinkit.media.MediaOutput
import com.haishinkit.media.MediaOutputDataSource
import com.haishinkit.media.MediaType
import com.haishinkit.screen.Screen
import com.haishinkit.screen.VideoScreenObject
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Stream class is the foundation of a [com.haishinkit.rtmp.RtmpStream].
 */
@Suppress("UNUSED")
abstract class Stream(
    private var context: Context,
) : MediaOutput,
    MediaOutputDataSource {
    /**
     * Specifies the video codec settings.
     */
    val videoSetting: VideoCodec.Setting by lazy {
        VideoCodec.Setting(videoCodec)
    }

    /**
     * Specifies the audio codec settings.
     */
    val audioSetting: AudioCodec.Setting by lazy {
        AudioCodec.Setting(audioCodec)
    }

    override var dataSource: WeakReference<MediaOutputDataSource>? = null
        set(value) {
            field = value
            videoCodec.pixelTransform.screen = value?.get()?.screen
            outputs.forEach {
                it.dataSource = value
            }
        }

    override var hasAudio: Boolean = false
        get() {
            if (dataSource != null) {
                return dataSource?.get()?.hasAudio == true
            }
            return field
        }

    override var hasVideo: Boolean = false
        get() {
            if (dataSource != null) {
                return dataSource?.get()?.hasVideo == true
            }
            return field
        }

    /**
     * When true, video input is provided externally via videoCodec.inputSurface
     * and the internal Screen/PixelTransform pipeline is bypassed.
     */
    var useExternalVideoInput: Boolean = false

    override val screen: Screen = Screen.create(context)
        get() {
            if (dataSource != null) {
                return dataSource?.get()?.screen ?: field
            }
            return field
        }

    val mediaLink: MediaLink by lazy {
        MediaLink(this)
    }

    protected val audioCodec by lazy { AudioCodec() }

    /**
     * The video codec instance.
     * Exposed for direct access to inputSurface in Unity integration.
     */
    val videoCodec by lazy { VideoCodec(context) }

    protected var mode = Codec.MODE_ENCODE
        set(value) {
            if (field == value) return
            audioCodec.mode = value
            videoCodec.mode = value
            field = value
        }

    protected var isRunning = AtomicBoolean(false)

    protected val video: VideoScreenObject by lazy {
        VideoScreenObject().apply {
            isRotatesWithContent = false
        }
    }

    private var outputs = mutableListOf<MediaOutput>()
    private var audioBufferPool = Pools.SynchronizedPool<MediaBuffer>(BUFFER_POOL_COUNTS)
    private var videoBufferPool = Pools.SynchronizedPool<MediaBuffer>(BUFFER_POOL_COUNTS)

    override fun registerOutput(output: MediaOutput) {
        if (!outputs.contains(output)) {
            output.dataSource = WeakReference(this)
            outputs.add(output)
        }
    }

    override fun unregisterOutput(output: MediaOutput) {
        if (outputs.contains(output)) {
            outputs.remove(output)
            output.dataSource = null
        }
    }

    /**
     * Closes the stream from the server.
     */
    abstract fun close()

    /**
     * Disposes the stream of memory management.
     */
    open fun dispose() {
        outputs.forEach {
            unregisterOutput(it)
        }
        audioCodec.dispose()
        videoCodec.dispose()
    }

    private var appendCallCount = 0

    override fun append(buffer: MediaBuffer) {
        appendCallCount++
        outputs.forEach {
            it.append(buffer)
        }
        if (!isRunning.get()) {
            if (appendCallCount <= 10 || appendCallCount % 500 == 0) {
                Log.d(TAG, "append #$appendCallCount: SKIPPED because isRunning=false, type=${buffer.type}")
            }
            return
        }
        when (buffer.type) {
            MediaType.AUDIO -> {
                buffer.payload?.let {
                    if (appendCallCount <= 10 || appendCallCount % 200 == 0) {
                        Log.d(TAG, "append #$appendCallCount: audioCodec.append() called, payload.remaining=${it.remaining()}")
                    }
                    audioCodec.append(it)
                }
            }

            MediaType.VIDEO -> {
            }
        }
    }

    fun queueOutputBuffer(
        type: MediaType,
        index: Int,
        payload: ByteBuffer?,
        timestamp: Long,
        sync: Boolean,
    ) {
        when (type) {
            MediaType.AUDIO -> {
                mediaLink.queueAudio(
                    (audioBufferPool.acquire() ?: MediaBuffer(type, 0, null, 0, false)).apply {
                        this.index = index
                        this.payload = payload
                        this.timestamp = timestamp
                        this.sync = sync
                    },
                )
            }

            MediaType.VIDEO -> {
                mediaLink.queueVideo(
                    (videoBufferPool.acquire() ?: MediaBuffer(type, 0, null, 0, false)).apply {
                        this.index = index
                        this.payload = payload
                        this.timestamp = timestamp
                        this.sync = sync
                    },
                )
            }
        }
    }

    internal fun releaseOutputBuffer(
        buffer: MediaBuffer,
        render: Boolean,
    ) {
        when (buffer.type) {
            MediaType.AUDIO -> {
                if (audioCodec.isRunning.get()) {
                    audioCodec.codec?.releaseOutputBuffer(buffer.index, false)
                }
                buffer.payload = null
                audioBufferPool.release(buffer)
            }

            MediaType.VIDEO -> {
                if (videoCodec.isRunning.get()) {
                    if (render) {
                        videoCodec.codec?.releaseOutputBuffer(buffer.index, buffer.timestamp * 1000)
                    } else {
                        videoCodec.codec?.releaseOutputBuffer(buffer.index, false)
                    }
                }
                videoBufferPool.release(buffer)
            }
        }
    }

    @Synchronized
    protected open fun startRunning() {
        if (isRunning.get()) return
        Log.d(TAG, "startRunning() mode=$mode, hasVideo=$hasVideo, hasAudio=$hasAudio, dataSource=$dataSource, useExternalVideoInput=$useExternalVideoInput")
        when (mode) {
            Codec.MODE_ENCODE -> {
                // useExternalVideoInputがtrueの場合はpixelTransform.screenを設定しない
                // 外部からvideoCodec.inputSurfaceに直接描画される
                if (!useExternalVideoInput && dataSource == null) {
                    Log.d(TAG, "startRunning() setting pixelTransform.screen, screen.frame=${screen.frame}")
                    videoCodec.pixelTransform.screen = screen
                } else if (useExternalVideoInput) {
                    Log.d(TAG, "startRunning() using external video input (bypassing PixelTransform)")
                }
                if (hasAudio) {
                    Log.d(TAG, "startRunning() starting audioCodec")
                    audioCodec.startRunning()
                }
                if (hasVideo) {
                    Log.d(TAG, "startRunning() starting videoCodec")
                    videoCodec.startRunning()
                } else {
                    Log.d(TAG, "startRunning() skipping videoCodec because hasVideo=false")
                }
            }

            Codec.MODE_DECODE -> {
                screen.addChild(video)
                mediaLink.startRunning()
            }
        }
        isRunning.set(true)
    }

    @Synchronized
    protected open fun stopRunning() {
        if (!isRunning.get()) return
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "stopRunning()")
        }
        when (mode) {
            Codec.MODE_ENCODE -> {
            }

            Codec.MODE_DECODE -> {
                mediaLink.stopRunning()
                screen.removeChild(video)
                hasAudio = false
                hasVideo = false
            }
        }
        audioCodec.listener = null
        audioCodec.stopRunning()
        videoCodec.listener = null
        videoCodec.stopRunning()
        isRunning.set(false)
    }

    private companion object {
        private const val BUFFER_POOL_COUNTS = 16
        private val TAG = Stream::class.java.simpleName
    }
}
