// Unity Native Plugin Interface
// From Unity's Native Plugin SDK

#pragma once

#include <stdint.h>

// Unity version defines
#define UNITY_INTERFACE_API
#define UNITY_INTERFACE_EXPORT __attribute__((visibility("default")))

// Basic types
typedef uint32_t UnityGfxDeviceEventType;

enum {
    kUnityGfxDeviceEventInitialize = 0,
    kUnityGfxDeviceEventShutdown = 1,
    kUnityGfxDeviceEventBeforeReset = 2,
    kUnityGfxDeviceEventAfterReset = 3,
};

typedef uint32_t UnityGfxRenderer;

enum {
    kUnityGfxRendererNull = 0,
    kUnityGfxRendererOpenGLES20 = 8,
    kUnityGfxRendererOpenGLES30 = 11,
    kUnityGfxRendererMetal = 16,
    kUnityGfxRendererVulkan = 21,
};

// Interface GUID
struct UnityInterfaceGUID {
    UnityInterfaceGUID(uint64_t high, uint64_t low) : m_GUIDHigh(high), m_GUIDLow(low) {}
    uint64_t m_GUIDHigh;
    uint64_t m_GUIDLow;
};

// Base interface
struct IUnityInterface {
    virtual ~IUnityInterface() {}
};

// Unity interfaces container
struct IUnityInterfaces {
    virtual IUnityInterface* GetInterface(UnityInterfaceGUID guid) = 0;
    virtual void RegisterInterface(UnityInterfaceGUID guid, IUnityInterface* ptr) = 0;

    template<typename T>
    T* Get() {
        return static_cast<T*>(GetInterface(T::GetGUID()));
    }
};

// Render event function pointer
typedef void (UNITY_INTERFACE_API *UnityRenderingEvent)(int eventId);
typedef void (UNITY_INTERFACE_API *UnityRenderingEventAndData)(int eventId, void* data);

// Plugin load/unload
extern "C" void UNITY_INTERFACE_EXPORT UNITY_INTERFACE_API UnityPluginLoad(IUnityInterfaces* unityInterfaces);
extern "C" void UNITY_INTERFACE_EXPORT UNITY_INTERFACE_API UnityPluginUnload();
