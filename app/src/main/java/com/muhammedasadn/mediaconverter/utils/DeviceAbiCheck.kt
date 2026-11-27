package com.muhammedasadn.mediaconverter.utils

import android.os.Build
import com.muhammedasadn.mediaconverter.HazelApp

/**
 * Detects native engine binary execution failures and ABI mismatches
 * (for example, running an arm64-v8a APK on an x86_64 PC emulator via ARM translation).
 */
internal object DeviceAbiCheck {

    private val BINARY_MARKERS = arrayOf(
        "libpython.so",
        "no closing quote",
        "exec format",
        "enoexec"
    )

    fun isEngineBinaryFailure(raw: String): Boolean {
        val lower = raw.lowercase()
        return BINARY_MARKERS.any { it in lower }
    }

    /** Normalizes the leaf directory of nativeLibraryDir (e.g. `arm64` -> `arm64-v8a`). */
    fun installedAbiFromDir(nativeLibraryDir: String?): String? {
        val leaf = nativeLibraryDir
            ?.trimEnd('/', '\\')
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        return when (leaf) {
            "arm64", "arm64-v8a" -> "arm64-v8a"
            "arm", "armeabi", "armeabi-v7a" -> "armeabi-v7a"
            "x86_64", "x64" -> "x86_64"
            "x86" -> "x86"
            else -> leaf
        }
    }

    /**
     * Returns `(installedAbi, devicePrimaryAbi)` when an ARM-only native library directory
     * is running on an x86/x86_64 host where ARM translation fails `execve` on `libpython.so`.
     */
    fun detectAbiMismatch(
        nativeLibraryDir: String?,
        supportedAbis: Array<String>
    ): Pair<String, String>? {
        val installed = installedAbiFromDir(nativeLibraryDir) ?: return null
        val devicePrimary = supportedAbis.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val isArmInstalled = installed == "arm64-v8a" || installed == "armeabi-v7a"
        val isX86Device = devicePrimary.startsWith("x86", ignoreCase = true)
        return if (isArmInstalled && isX86Device) installed to devicePrimary else null
    }

    fun engineFailureMessage(
        nativeLibraryDir: String?,
        supportedAbis: Array<String>
    ): String {
        val mismatch = detectAbiMismatch(nativeLibraryDir, supportedAbis)
        return if (mismatch != null) {
            val (installed, device) = mismatch
            "Wrong APK installed ($installed APK on $device device/emulator). Uninstall and install the $device or universal APK"
        } else {
            "Engine failed to start on this device. Reinstall or use the universal APK"
        }
    }

    fun engineFailureMessage(): String {
        val nativeDir = runCatching { HazelApp.instance.applicationInfo?.nativeLibraryDir }.getOrNull()
        val abis = runCatching { Build.SUPPORTED_ABIS }.getOrElse { emptyArray() }
        return engineFailureMessage(nativeDir, abis)
    }
}
