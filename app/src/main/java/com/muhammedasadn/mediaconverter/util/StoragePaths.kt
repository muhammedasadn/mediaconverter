package com.muhammedasadn.mediaconverter.util

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * StoragePaths — single source of truth for all Hazel folder locations.
 *
 * Two-tier approach:
 *   • TEMP paths: app-specific external (getExternalFilesDir) — always writable, NO permission
 *   • FINAL paths: public shared storage (Download/MediaConverter, Music/MediaConverter) — via MediaStore move
 *
 * Layout (temp — where yt-dlp writes):
 *   Android/data/com.muhammedasadn.mediaconverter/files/Hazel/         ← downloads
 *   Android/data/com.muhammedasadn.mediaconverter/files/Music/          ← converter output
 *
 * Layout (final — where user sees files):
 *   /storage/emulated/0/Download/MediaConverter/                  ← downloads
 *   /storage/emulated/0/Music/MediaConverter/                     ← converter output
 *
 */
object StoragePaths {

    private lateinit var appContext: Context

    /** Must be called once from HazelApp.onCreate() */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // ── TEMP paths (yt-dlp/ffmpeg write here, always writable, no permission) ──

    /** Temp download dir: Android/data/com.muhammedasadn.mediaconverter/files/Hazel/ */
    val tempDownloads: File
        get() = ensureDir(
            File(appContext.getExternalFilesDir(null), "Hazel")
        )

    /** Temp converter output: Android/data/com.muhammedasadn.mediaconverter/files/Music/ */
    val tempConverted: File
        get() = ensureDir(
            File(appContext.getExternalFilesDir(null), "Music")
        )

    // ── FINAL paths (public shared storage, where files end up after MediaStore move) ──

    /** Final download dir: /storage/emulated/0/Download/MediaConverter/ */
    val finalDownloads: File
        get() = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Hazel"
        )

    /** Where one kind lands by default: Download/MediaConverter/Audio or Download/MediaConverter/Video. */
    fun finalDownloads(isAudio: Boolean): File =
        File(finalDownloads, if (isAudio) "Audio" else "Video")

    /** Final converter dir: /storage/emulated/0/Music/MediaConverter/ */
    val finalConverted: File
        get() = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "Hazel"
        )

    // ── Display paths (shown in UI) ──

    const val DOWNLOADS_DISPLAY = "Download/MediaConverter"
    const val DOWNLOADS_VIDEO_DISPLAY = "Download/MediaConverter/Video"
    const val DOWNLOADS_AUDIO_DISPLAY = "Download/MediaConverter/Audio"
    const val DOWNLOADS_GALLERY_DISPLAY = "Download/MediaConverter/Gallery"
    const val CONVERTED_DISPLAY = "Music/MediaConverter"

    // ── MediaStore relative paths (used for ContentValues) ──

    /** Relative path for MediaStore.Downloads: "Download/MediaConverter" */
    const val DOWNLOAD_RELATIVE_PATH = "Download/MediaConverter"
    const val DOWNLOAD_VIDEO_RELATIVE_PATH = "Download/MediaConverter/Video"
    const val DOWNLOAD_AUDIO_RELATIVE_PATH = "Download/MediaConverter/Audio"
    const val DOWNLOAD_GALLERY_RELATIVE_PATH = "Download/MediaConverter/Gallery"

    /**
     * Where a download lands when the user has not picked a folder: audio and video are kept
     * apart under the app's download folder, so a music library and a video library can each
     * be pointed at their own.
     */
    fun downloadRelativePath(isAudio: Boolean): String =
        if (isAudio) DOWNLOAD_AUDIO_RELATIVE_PATH else DOWNLOAD_VIDEO_RELATIVE_PATH

    /** [downloadRelativePath] as the UI shows it. */
    fun downloadsDisplay(isAudio: Boolean): String =
        if (isAudio) DOWNLOADS_AUDIO_DISPLAY else DOWNLOADS_VIDEO_DISPLAY

    /** Relative path for MediaStore.Audio: "Music/MediaConverter" */
    const val MUSIC_RELATIVE_PATH = "Music/MediaConverter"

    private fun ensureDir(dir: File): File {
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
