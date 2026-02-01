/**
 * Unity Native Rendering Plugin for HaishinKit
 *
 * This plugin enables zero-copy texture sharing between Unity and MediaCodec
 * by running on Unity's render thread with access to the same EGL context.
 *
 * Architecture:
 * 1. Unity calls GL.IssuePluginEvent() from render thread
 * 2. This plugin receives the callback with Unity's EGL context active
 * 3. Plugin binds Unity's texture and renders to MediaCodec's Surface
 * 4. No CPU memory copy required (true zero-copy)
 */

#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <cstdlib>
#include <cstring>
#include <mutex>
#include <atomic>

// Unity plugin interface macros
#define UNITY_INTERFACE_API
#define UNITY_INTERFACE_EXPORT __attribute__((visibility("default")))

// Unity render event function pointer type
typedef void (*UnityRenderingEvent)(int eventId);

#define LOG_TAG "NativeTexturePlugin"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// Rendering state
static std::mutex s_Mutex;
static std::atomic<bool> s_Initialized{false};
static std::atomic<bool> s_RenderPending{false};

// Texture and surface info
static GLuint s_UnityTextureId = 0;
static int s_TextureWidth = 0;
static int s_TextureHeight = 0;

// EGL resources for MediaCodec surface
static EGLDisplay s_EglDisplay = EGL_NO_DISPLAY;
static EGLSurface s_EglSurface = EGL_NO_SURFACE;
static EGLContext s_EglContext = EGL_NO_CONTEXT;
static EGLConfig s_EglConfig = nullptr;
static ANativeWindow* s_NativeWindow = nullptr;

// OpenGL resources
static GLuint s_Program = 0;
static GLuint s_VBO = 0;
static GLint s_PositionLoc = -1;
static GLint s_TexCoordLoc = -1;
static GLint s_TextureLoc = -1;

// Frame counter
static int s_FrameCount = 0;
static int64_t s_StartTimeNanos = 0;

// Shader sources
static const char* VERTEX_SHADER_SOURCE = R"(
    attribute vec4 aPosition;
    attribute vec2 aTexCoord;
    varying vec2 vTexCoord;
    void main() {
        gl_Position = aPosition;
        vTexCoord = aTexCoord;
    }
)";

static const char* FRAGMENT_SHADER_SOURCE = R"(
    precision mediump float;
    varying vec2 vTexCoord;
    uniform sampler2D uTexture;

    void main() {
        vec4 color = texture2D(uTexture, vTexCoord);
        // Linear to sRGB gamma correction (gamma 2.2 approximation)
        // Using max() to ensure non-negative values for pow() - some GPUs have undefined behavior with negative inputs
        vec3 clamped = max(color.rgb, vec3(0.0));
        vec3 srgb = pow(clamped, vec3(0.4545));  // 1.0/2.2 = 0.4545
        gl_FragColor = vec4(srgb, color.a);
    }
)";

// Vertex data (fullscreen quad - no Y flip needed for Unity RenderTexture)
static const float VERTEX_DATA[] = {
    // Position      // TexCoord (standard mapping)
    -1.0f, -1.0f,    0.0f, 0.0f,
     1.0f, -1.0f,    1.0f, 0.0f,
    -1.0f,  1.0f,    0.0f, 1.0f,
     1.0f,  1.0f,    1.0f, 1.0f,
};

// Helper function to compile shader
static GLuint CompileShader(GLenum type, const char* source) {
    // Check for GL errors before creating shader
    GLenum preError = glGetError();
    if (preError != GL_NO_ERROR) {
        LOGW("CompileShader: pre-existing GL error: 0x%x", preError);
    }

    GLuint shader = glCreateShader(type);
    if (shader == 0) {
        LOGE("CompileShader: glCreateShader failed, type=%s, error=0x%x",
             type == GL_VERTEX_SHADER ? "VERTEX" : "FRAGMENT", glGetError());
        return 0;
    }

    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);

    GLint compiled = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        GLint infoLen = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &infoLen);
        char infoLog[512] = {0};
        if (infoLen > 0) {
            glGetShaderInfoLog(shader, sizeof(infoLog) - 1, nullptr, infoLog);
        }
        LOGE("Shader compile error (%s): %s",
             type == GL_VERTEX_SHADER ? "VERTEX" : "FRAGMENT",
             infoLen > 0 ? infoLog : "unknown error");
        glDeleteShader(shader);
        return 0;
    }

    LOGD("CompileShader: %s shader compiled successfully",
         type == GL_VERTEX_SHADER ? "VERTEX" : "FRAGMENT");
    return shader;
}

// Helper function to create shader program
static GLuint CreateProgram() {
    GLuint vertexShader = CompileShader(GL_VERTEX_SHADER, VERTEX_SHADER_SOURCE);
    if (vertexShader == 0) return 0;

    GLuint fragmentShader = CompileShader(GL_FRAGMENT_SHADER, FRAGMENT_SHADER_SOURCE);
    if (fragmentShader == 0) {
        glDeleteShader(vertexShader);
        return 0;
    }

    GLuint program = glCreateProgram();
    glAttachShader(program, vertexShader);
    glAttachShader(program, fragmentShader);
    glLinkProgram(program);

    GLint linked = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!linked) {
        GLint infoLen = 0;
        glGetProgramiv(program, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 0) {
            char* infoLog = new char[infoLen];
            glGetProgramInfoLog(program, infoLen, nullptr, infoLog);
            LOGE("Program link error: %s", infoLog);
            delete[] infoLog;
        }
        glDeleteProgram(program);
        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);
        return 0;
    }

    glDeleteShader(vertexShader);
    glDeleteShader(fragmentShader);

    return program;
}

// Initialize OpenGL resources
static bool InitializeGL() {
    LOGD("InitializeGL");

    // Check EGL context is valid
    EGLContext ctx = eglGetCurrentContext();
    EGLDisplay dpy = eglGetCurrentDisplay();
    if (ctx == EGL_NO_CONTEXT || dpy == EGL_NO_DISPLAY) {
        LOGE("InitializeGL: No valid EGL context (ctx=%p, dpy=%p)", ctx, dpy);
        return false;
    }

    // Check GL version info
    const char* version = (const char*)glGetString(GL_VERSION);
    const char* renderer = (const char*)glGetString(GL_RENDERER);
    LOGD("InitializeGL: GL_VERSION=%s, GL_RENDERER=%s",
         version ? version : "null", renderer ? renderer : "null");

    // Create shader program
    s_Program = CreateProgram();
    if (s_Program == 0) {
        LOGE("Failed to create shader program");
        return false;
    }

    // Get attribute and uniform locations
    s_PositionLoc = glGetAttribLocation(s_Program, "aPosition");
    s_TexCoordLoc = glGetAttribLocation(s_Program, "aTexCoord");
    s_TextureLoc = glGetUniformLocation(s_Program, "uTexture");

    // Create VBO
    glGenBuffers(1, &s_VBO);
    glBindBuffer(GL_ARRAY_BUFFER, s_VBO);
    glBufferData(GL_ARRAY_BUFFER, sizeof(VERTEX_DATA), VERTEX_DATA, GL_STATIC_DRAW);
    glBindBuffer(GL_ARRAY_BUFFER, 0);

    LOGD("InitializeGL: success, program=%d", s_Program);
    return true;
}

// Cleanup OpenGL resources
static void CleanupGL() {
    LOGD("CleanupGL");

    if (s_VBO != 0) {
        glDeleteBuffers(1, &s_VBO);
        s_VBO = 0;
    }

    if (s_Program != 0) {
        glDeleteProgram(s_Program);
        s_Program = 0;
    }
}

// Initialize EGL for MediaCodec surface
static bool InitializeEGLSurface() {
    if (s_NativeWindow == nullptr) {
        LOGE("InitializeEGLSurface: no native window");
        return false;
    }

    LOGD("InitializeEGLSurface: %dx%d", s_TextureWidth, s_TextureHeight);

    // Get current EGL context info (Unity's context)
    s_EglDisplay = eglGetCurrentDisplay();
    s_EglContext = eglGetCurrentContext();

    if (s_EglDisplay == EGL_NO_DISPLAY || s_EglContext == EGL_NO_CONTEXT) {
        LOGE("InitializeEGLSurface: no current EGL context");
        return false;
    }

    // Get the config from current context
    EGLint numConfigs;
    EGLint configAttribs[] = {
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_NONE
    };

    EGLConfig configs[1];
    if (!eglChooseConfig(s_EglDisplay, configAttribs, configs, 1, &numConfigs) || numConfigs == 0) {
        LOGE("InitializeEGLSurface: eglChooseConfig failed");
        return false;
    }
    s_EglConfig = configs[0];

    // Create window surface for MediaCodec
    EGLint surfaceAttribs[] = { EGL_NONE };
    s_EglSurface = eglCreateWindowSurface(s_EglDisplay, s_EglConfig, s_NativeWindow, surfaceAttribs);
    if (s_EglSurface == EGL_NO_SURFACE) {
        LOGE("InitializeEGLSurface: eglCreateWindowSurface failed, error=0x%x", eglGetError());
        return false;
    }

    LOGD("InitializeEGLSurface: success, surface=%p", s_EglSurface);
    return true;
}

// Cleanup EGL surface
static void CleanupEGLSurface() {
    LOGD("CleanupEGLSurface");

    if (s_EglSurface != EGL_NO_SURFACE && s_EglDisplay != EGL_NO_DISPLAY) {
        eglDestroySurface(s_EglDisplay, s_EglSurface);
        s_EglSurface = EGL_NO_SURFACE;
    }

    if (s_NativeWindow != nullptr) {
        ANativeWindow_release(s_NativeWindow);
        s_NativeWindow = nullptr;
    }
}

// Render Unity texture to MediaCodec surface
static void RenderFrame() {
    if (!s_Initialized.load() || s_UnityTextureId == 0 || s_EglSurface == EGL_NO_SURFACE) {
        return;
    }

    s_FrameCount++;

    // Save current EGL state (Unity's context)
    EGLDisplay prevDisplay = eglGetCurrentDisplay();
    EGLSurface prevDrawSurface = eglGetCurrentSurface(EGL_DRAW);
    EGLSurface prevReadSurface = eglGetCurrentSurface(EGL_READ);
    EGLContext prevContext = eglGetCurrentContext();

    if (prevDisplay == EGL_NO_DISPLAY || prevContext == EGL_NO_CONTEXT) {
        if (s_FrameCount <= 5) {
            LOGE("RenderFrame: no current EGL context!");
        }
        return;
    }

    // Save current GL state that we'll modify
    GLint prevFramebuffer = 0;
    GLint prevProgram = 0;
    GLint prevActiveTexture = 0;
    GLint prevTexture = 0;
    GLint prevArrayBuffer = 0;
    GLint prevViewport[4];
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &prevFramebuffer);
    glGetIntegerv(GL_CURRENT_PROGRAM, &prevProgram);
    glGetIntegerv(GL_ACTIVE_TEXTURE, &prevActiveTexture);
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &prevTexture);
    glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &prevArrayBuffer);
    glGetIntegerv(GL_VIEWPORT, prevViewport);

    // Ensure Unity's rendering to the texture is complete
    glFinish();

    // Unbind current framebuffer to make texture accessible for sampling
    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    // Switch to MediaCodec surface using CURRENT context (not stored one)
    // This ensures Unity's texture is accessible
    if (!eglMakeCurrent(prevDisplay, s_EglSurface, s_EglSurface, prevContext)) {
        if (s_FrameCount <= 5) {
            LOGE("RenderFrame: eglMakeCurrent failed, error=0x%x", eglGetError());
        }
        // Restore state before returning
        glBindFramebuffer(GL_FRAMEBUFFER, prevFramebuffer);
        return;
    }

    // Clear
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    // Set viewport
    glViewport(0, 0, s_TextureWidth, s_TextureHeight);

    // Use shader program
    glUseProgram(s_Program);

    // Bind Unity's texture
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, s_UnityTextureId);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glUniform1i(s_TextureLoc, 0);

    // Check for errors
    GLenum error = glGetError();
    if (error != GL_NO_ERROR && (s_FrameCount <= 5 || s_FrameCount % 100 == 0)) {
        LOGW("RenderFrame: GL error after texture bind: 0x%x", error);
    }

    // Setup vertex attributes
    glBindBuffer(GL_ARRAY_BUFFER, s_VBO);
    glEnableVertexAttribArray(s_PositionLoc);
    glVertexAttribPointer(s_PositionLoc, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*)0);
    glEnableVertexAttribArray(s_TexCoordLoc);
    glVertexAttribPointer(s_TexCoordLoc, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*)(2 * sizeof(float)));

    // Draw
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    // Disable attributes
    glDisableVertexAttribArray(s_PositionLoc);
    glDisableVertexAttribArray(s_TexCoordLoc);
    glBindBuffer(GL_ARRAY_BUFFER, 0);

    // Set presentation timestamp
    if (s_StartTimeNanos == 0) {
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        s_StartTimeNanos = ts.tv_sec * 1000000000LL + ts.tv_nsec;
    }
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    int64_t presentationTimeNanos = (ts.tv_sec * 1000000000LL + ts.tv_nsec) - s_StartTimeNanos;

    // Use EGL_ANDROID_presentation_time extension
    typedef EGLBoolean (*PFNEGLPRESENTATIONTIMEANDROIDPROC)(EGLDisplay, EGLSurface, EGLnsecsANDROID);
    static PFNEGLPRESENTATIONTIMEANDROIDPROC eglPresentationTimeANDROID = nullptr;
    if (eglPresentationTimeANDROID == nullptr) {
        eglPresentationTimeANDROID = (PFNEGLPRESENTATIONTIMEANDROIDPROC)eglGetProcAddress("eglPresentationTimeANDROID");
    }
    if (eglPresentationTimeANDROID != nullptr) {
        eglPresentationTimeANDROID(prevDisplay, s_EglSurface, presentationTimeNanos);
    }

    // Swap buffers (send frame to MediaCodec)
    eglSwapBuffers(prevDisplay, s_EglSurface);

    // Ensure our rendering is complete before switching back
    glFinish();

    // Restore previous EGL state
    eglMakeCurrent(prevDisplay, prevDrawSurface, prevReadSurface, prevContext);

    // Restore all GL state
    glBindFramebuffer(GL_FRAMEBUFFER, prevFramebuffer);
    glUseProgram(prevProgram);
    glActiveTexture(prevActiveTexture);
    glBindTexture(GL_TEXTURE_2D, prevTexture);
    glBindBuffer(GL_ARRAY_BUFFER, prevArrayBuffer);
    glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);

    if (s_FrameCount <= 5 || s_FrameCount % 100 == 0) {
        LOGD("RenderFrame: frame #%d, textureId=%d, pts=%lldms",
             s_FrameCount, s_UnityTextureId, presentationTimeNanos / 1000000LL);
    }
}

// Flag for lazy GL initialization
static std::atomic<bool> s_GLInitialized{false};

// Unity render event callback (called from GL.IssuePluginEvent)
static void UNITY_INTERFACE_API OnRenderEvent(int eventID) {
    switch (eventID) {
        case 0:
            // eventID 0 = render frame
            // Lazy initialize GL resources on first render
            if (!s_GLInitialized.load()) {
                LOGD("OnRenderEvent: lazy initializing GL resources");
                if (InitializeGL()) {
                    s_GLInitialized.store(true);
                } else {
                    LOGE("OnRenderEvent: failed to initialize GL");
                    return;
                }
            }
            RenderFrame();
            break;
        case 1:
            // eventID 1 = initialize EGL surface (must be called on render thread)
            {
                std::lock_guard<std::mutex> lock(s_Mutex);
                if (s_EglSurface == EGL_NO_SURFACE && s_NativeWindow != nullptr) {
                    LOGD("OnRenderEvent: initializing EGL surface on render thread");
                    InitializeEGLSurface();
                }
            }
            break;
        default:
            break;
    }
}

// ============================================================================
// Unity Plugin Entry Points
// ============================================================================

extern "C" {

// Get render event callback function pointer
// Note: We don't use UnityPluginLoad/Unload to avoid compatibility issues
// with Unity's IUnityInterfaces. Instead, we initialize lazily in OnRenderEvent.
UnityRenderingEvent UNITY_INTERFACE_EXPORT UNITY_INTERFACE_API GetRenderEventFunc() {
    LOGD("GetRenderEventFunc called");
    return OnRenderEvent;
}

} // extern "C"

// ============================================================================
// JNI Functions (called from Kotlin/Java)
// ============================================================================

extern "C" {

JNIEXPORT void JNICALL
Java_com_haishinkit_unity_NativeTexturePlugin_nativeSetSurface(
        JNIEnv* env, jclass clazz, jobject surface, jint width, jint height) {
    std::lock_guard<std::mutex> lock(s_Mutex);

    LOGD("nativeSetSurface: %dx%d, surface=%p", width, height, surface);

    // Cleanup previous surface
    CleanupEGLSurface();

    if (surface != nullptr) {
        s_NativeWindow = ANativeWindow_fromSurface(env, surface);
        s_TextureWidth = width;
        s_TextureHeight = height;

        LOGD("nativeSetSurface: nativeWindow=%p", s_NativeWindow);
    }
}

JNIEXPORT void JNICALL
Java_com_haishinkit_unity_NativeTexturePlugin_nativeSetTextureId(
        JNIEnv* env, jclass clazz, jint textureId) {
    s_UnityTextureId = (GLuint)textureId;

    if (s_FrameCount <= 5 || s_FrameCount % 100 == 0) {
        LOGD("nativeSetTextureId: %d", textureId);
    }
}

JNIEXPORT void JNICALL
Java_com_haishinkit_unity_NativeTexturePlugin_nativeInitialize(
        JNIEnv* env, jclass clazz) {
    std::lock_guard<std::mutex> lock(s_Mutex);
    LOGD("nativeInitialize");

    // EGL surface will be initialized on first render (when we have Unity's context)
    s_Initialized.store(true);
    s_FrameCount = 0;
    s_StartTimeNanos = 0;
}

JNIEXPORT void JNICALL
Java_com_haishinkit_unity_NativeTexturePlugin_nativeInitializeEGL(
        JNIEnv* env, jclass clazz) {
    // This is called from Unity's render thread via GL.IssuePluginEvent
    std::lock_guard<std::mutex> lock(s_Mutex);
    LOGD("nativeInitializeEGL (on render thread)");

    if (s_EglSurface == EGL_NO_SURFACE && s_NativeWindow != nullptr) {
        InitializeEGLSurface();
    }
}

JNIEXPORT void JNICALL
Java_com_haishinkit_unity_NativeTexturePlugin_nativeCleanup(
        JNIEnv* env, jclass clazz) {
    std::lock_guard<std::mutex> lock(s_Mutex);
    LOGD("nativeCleanup");

    s_Initialized.store(false);
    s_GLInitialized.store(false);
    CleanupGL();
    CleanupEGLSurface();
    s_UnityTextureId = 0;
    s_FrameCount = 0;
    s_StartTimeNanos = 0;
}

JNIEXPORT jboolean JNICALL
Java_com_haishinkit_unity_NativeTexturePlugin_nativeIsReady(
        JNIEnv* env, jclass clazz) {
    return s_Initialized.load() && s_EglSurface != EGL_NO_SURFACE && s_Program != 0;
}

} // extern "C"
