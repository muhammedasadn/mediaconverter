package com.muhammedasadn.mediaconverter.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceAbiCheckTest {

    @Test
    fun detectsEngineBinaryFailures() {
        assertTrue(DeviceAbiCheck.isEngineBinaryFailure("/data/app/pkg/lib/arm64/libpython.so[6]: no closing quote"))
        assertTrue(DeviceAbiCheck.isEngineBinaryFailure("Cannot run program: Exec format error"))
        assertFalse(DeviceAbiCheck.isEngineBinaryFailure("HTTP Error 403: Forbidden"))
    }

    @Test
    fun detectsArmApkOnX86Emulator() {
        val dir = "/data/app/com.muhammedasadn.mediaconverter-abc==/lib/arm64"
        val abis = arrayOf("x86_64", "arm64-v8a", "x86", "armeabi-v7a")

        val mismatch = DeviceAbiCheck.detectAbiMismatch(dir, abis)
        assertEquals("arm64-v8a" to "x86_64", mismatch)

        val message = DeviceAbiCheck.engineFailureMessage(dir, abis)
        assertTrue("arm64-v8a" in message)
        assertTrue("x86_64" in message)
        assertTrue("universal" in message)
    }

    @Test
    fun allowsNativeArmDeviceWithoutMismatch() {
        val dir = "/data/app/~~hash==/com.muhammedasadn.mediaconverter-abc==/lib/arm64"
        val abis = arrayOf("arm64-v8a", "armeabi-v7a", "armeabi")

        assertNull(DeviceAbiCheck.detectAbiMismatch(dir, abis))
        assertEquals(
            "Engine failed to start on this device. Reinstall or use the universal APK",
            DeviceAbiCheck.engineFailureMessage(dir, abis)
        )
    }

    @Test
    fun allowsX8664ApkOnX8664Emulator() {
        val dir = "/data/app/com.muhammedasadn.mediaconverter-abc==/lib/x86_64"
        val abis = arrayOf("x86_64", "arm64-v8a")

        assertNull(DeviceAbiCheck.detectAbiMismatch(dir, abis))
    }
}
