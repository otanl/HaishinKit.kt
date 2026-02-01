// Unity Graphics Interface
// From Unity's Native Plugin SDK

#pragma once

#include "IUnityInterface.h"

// Graphics device event callback
typedef void (UNITY_INTERFACE_API *IUnityGraphicsDeviceEventCallback)(UnityGfxDeviceEventType eventType);

// Graphics interface
struct IUnityGraphics : IUnityInterface {
    virtual UnityGfxRenderer UNITY_INTERFACE_API GetRenderer() = 0;
    virtual void UNITY_INTERFACE_API RegisterDeviceEventCallback(IUnityGraphicsDeviceEventCallback callback) = 0;
    virtual void UNITY_INTERFACE_API UnregisterDeviceEventCallback(IUnityGraphicsDeviceEventCallback callback) = 0;
    virtual int UNITY_INTERFACE_API ReserveEventIDRange(int count) = 0;

    static UnityInterfaceGUID GetGUID() {
        return UnityInterfaceGUID(0x7CBA0A9CA4DDB544ULL, 0x8C5AD4926EB17B11ULL);
    }
};
