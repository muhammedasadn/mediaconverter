package com.muhammedasadn.mediaconverter

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import android.util.Log
import com.muhammedasadn.mediaconverter.download.NetworkProxy
import com.muhammedasadn.mediaconverter.download.NetworkProxy.throughProxy
import com.muhammedasadn.mediaconverter.download.WebViewProxy
import com.muhammedasadn.mediaconverter.gallery.queue.GalleryQueue
import com.muhammedasadn.mediaconverter.update.GalleryDlUpdater
import com.muhammedasadn.mediaconverter.update.YtDlpUpdater
import com.muhammedasadn.mediaconverter.utils.CrashLogger
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicBoolean

class HazelApp : Application(), SingletonImageLoader.Factory {

    /**
     * Thumbnails come from arbitrary media hosts, so the shared loader is given an explicit
     * network fetcher rather than relying on whatever Coil can auto-detect. Its client goes
     * through the proxy like every other connection.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { OkHttpClient.Builder().throughProxy().build() })) }
            .crossfade(true)
            .build()


    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Whether [startLibraryInit] has already run. */
    private val libraryInitStarted = AtomicBoolean(false)

    /** Completed once local engine unpacking and validation finishes (or fails). */
    private val libraryReady = CompletableDeferred<Unit>()

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize storage paths for downloads/conversions
        com.muhammedasadn.mediaconverter.util.StoragePaths.init(this)
        com.muhammedasadn.mediaconverter.util.SdCards.init(this)

        // Before anything connects: every connection outside yt-dlp asks this selector, which
        // reads the proxy setting, waiting for storage the first time if it has to.
        com.muhammedasadn.mediaconverter.download.AdvancedSettingsStore.loader = {
            runBlocking { com.muhammedasadn.mediaconverter.data.SettingsRepository.getAdvancedSettings(this@HazelApp).first() }
        }
        NetworkProxy.install()

        // Install crash logger — captures uncaught exceptions in-memory
        CrashLogger.install(this)

        applicationScope.launch { com.muhammedasadn.mediaconverter.data.SettingsRepository.applyUpdateNoticesOff(this@HazelApp) }
        applicationScope.launch(kotlinx.coroutines.Dispatchers.IO) { com.muhammedasadn.mediaconverter.util.AppShortcuts.publish(this@HazelApp) }
        applicationScope.launch { com.muhammedasadn.mediaconverter.data.BackupRepository.onLaunch(this@HazelApp) }

        // Unpacking yt-dlp and FFmpeg is the heaviest thing the app ever does at launch,
        // and starting it here put it in competition with the work that gets the first
        // frame on screen. The UI asks for it once it has drawn instead. This is the
        // fallback for a start with no UI, such as a notification action resuming a
        // download after the process was killed. Whenever there is a UI it wins this
        // race, because the call only acts the first time.
        applicationScope.launch {
            delay(LIBRARY_INIT_FALLBACK_MS)
            startLibraryInit()
        }

        // The advanced engine settings are read by every request, so they are held in
        // memory and kept in step with storage from here on.
        applicationScope.launch {
            var webProxy = ""
            com.muhammedasadn.mediaconverter.data.SettingsRepository.getAdvancedSettings(this@HazelApp).collect {
                com.muhammedasadn.mediaconverter.download.AdvancedSettingsStore.current = it
                // Web views take the proxy on the main thread, and only when it changes, so
                // nothing is done for them until a proxy has been set.
                val proxy = NetworkProxy.parse(it.activeProxy)
                if (proxy?.url.orEmpty() != webProxy) {
                    webProxy = proxy?.url.orEmpty()
                    withContext(Dispatchers.Main) { WebViewProxy.apply(proxy) }
                }
            }
        }
        // Incognito decides whether a read may be written to disk, which is asked from
        // places that cannot wait on storage, so it is held in memory the same way.
        applicationScope.launch {
            com.muhammedasadn.mediaconverter.data.SettingsRepository.getIncognito(this@HazelApp).collect {
                com.muhammedasadn.mediaconverter.download.ReadProfile.incognito = it
            }
        }
    }

    /**
     * Unpack and update the download engine, once per process.
     *
     * Called from the UI as soon as it has drawn, so the work happens in the gap where the
     * user is looking at a finished screen rather than in the gap where they are waiting
     * for one. Safe to call from anywhere and as often as anything likes: every call after
     * the first returns immediately.
     */
    fun startLibraryInit() {
        if (!libraryInitStarted.compareAndSet(false, true)) return

        applicationScope.launch {
            try {
                YoutubeDL.getInstance().init(this@HazelApp)
                FFmpeg.getInstance().init(this@HazelApp)
                // A binary cut short by an older build's in-place update fails every run,
                // so it is replaced with the bundled copy before anything uses it.
                YtDlpUpdater.ensureValidBinary(this@HazelApp)
                // A build fetched last session while something was downloading goes live
                // now, before this session's first run.
                YtDlpUpdater.applyStagedUpdate(this@HazelApp)
                Log.i("Hazel", "yt-dlp + FFmpeg initialized")
            } catch (e: Exception) {
                Log.e("Hazel", "Library init failed: ${e.message}")
                return@launch
            } finally {
                libraryReady.complete(Unit)
            }

            // Fetched in the background and swapped in only while nothing is running, so
            // a download started meanwhile keeps the binary it started with.
            YtDlpUpdater.autoUpdateIfAvailable(this@HazelApp)
            GalleryDlUpdater.applyStagedUpdate(this@HazelApp)
            GalleryQueue.restore(this@HazelApp)
            GalleryDlUpdater.autoUpdateIfAvailable(this@HazelApp)
        }
    }

    /**
     * Ensures local yt-dlp/FFmpeg initialization has completed before running an engine command.
     * Safe to call from any worker thread; starts initialization immediately if not yet started.
     */
    fun awaitLibraryReadyBlocking(timeoutMs: Long = 30_000L) {
        if (libraryReady.isCompleted) return
        startLibraryInit()
        runBlocking { withTimeoutOrNull(timeoutMs) { libraryReady.await() } }
    }

    companion object {
        lateinit var instance: HazelApp
            private set

        /**
         * How long to wait for the UI to ask for the engine before starting it anyway.
         *
         * Long enough that a normal launch always gets there first, short enough that a
         * start with no UI is not left waiting on it.
         */
        private const val LIBRARY_INIT_FALLBACK_MS = 5_000L
    }
}
