package com.muhammedasadn.mediaconverter.download.extractor.newpipe

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat as AndroidMediaFormat
import android.media.MediaMuxer
import com.muhammedasadn.mediaconverter.download.MediaFormat
import com.muhammedasadn.mediaconverter.download.NetworkProxy.throughProxy
import com.muhammedasadn.mediaconverter.download.playback.AudioTracks
import com.muhammedasadn.mediaconverter.download.playback.AudioWish
import com.muhammedasadn.mediaconverter.download.playback.StreamResolver
import kotlinx.coroutines.CancellationException
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.MediaFormat as NewPipeFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * Pure-JVM stream downloader & Android MediaMuxer fallback used when the native yt-dlp
 * binary cannot start on a device (for example, an arm64 APK running on an x86_64 emulator).
 */
internal object NewPipeDirectDownloader {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .throughProxy()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun download(
        url: String,
        requestedFormat: MediaFormat,
        title: String,
        audioLanguage: String,
        outputDir: File,
        isCancelled: () -> Boolean,
        onProgress: (fraction: Float, doneBytes: Long, totalBytes: Long, status: String) -> Unit
    ): File {
        val service = NewPipeLister.service(url)
            ?: error("No NewPipe service available for URL")
        val info = StreamInfo.getInfo(service, url)
        val wish = AudioWish(
            language = audioLanguage.takeIf { it.isNotBlank() && it != AudioTracks.ORIGINAL }
                ?: StreamResolver.wantedLanguage(AudioTracks.ORIGINAL)
        )
        val baseName = safeFileName(title.ifBlank { info.name.orEmpty() })
        if (!outputDir.exists()) outputDir.mkdirs()

        val targetItag = requestedFormat.formatId.toIntOrNull()
        val audioStreams = info.audioStreams.orEmpty().filter { it.isPlainHttp() }
        val pickedAudio = targetItag
            ?.takeIf { !requestedFormat.hasVideo }
            ?.let { itag -> audioStreams.firstOrNull { it.itag == itag } }
            ?: chooseAudio(audioStreams, wish)

        if (!requestedFormat.hasVideo) {
            val audio = pickedAudio ?: error("No direct audio stream available")
            val ext = audio.format?.suffix?.takeIf { it.isNotBlank() } ?: "m4a"
            val outFile = uniqueFile(outputDir, baseName, ext)
            val partFile = File(outputDir, "$baseName.$ext.part")
            try {
                downloadToFile(audio.content, partFile, isCancelled) { done, total ->
                    val frac = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                    onProgress(frac, done, total, "Downloading audio")
                }
                if (!partFile.renameTo(outFile)) {
                    partFile.copyTo(outFile, overwrite = true)
                    partFile.delete()
                }
                return outFile
            } finally {
                partFile.delete()
            }
        }

        val maxHeight = requestedFormat.height.takeIf { it > 0 } ?: Int.MAX_VALUE
        val videoOnly = info.videoOnlyStreams.orEmpty().filter { it.isPlainHttp() && it.qualityPx() > 0 }
        val muxed = info.videoStreams.orEmpty().filter { it.isPlainHttp() && it.qualityPx() > 0 }

        val pickedVideoOnly = targetItag
            ?.let { itag -> videoOnly.firstOrNull { it.itag == itag } }
            ?: pickBestVideo(videoOnly, maxHeight, preferMp4 = true)
        val pickedMuxed = targetItag
            ?.let { itag -> muxed.firstOrNull { it.itag == itag } }
            ?: pickBestVideo(muxed, maxHeight, preferMp4 = true)

        // Adaptive MP4 video + M4A audio muxed via Android's built-in MediaMuxer gives HD+ downloads.
        val m4aAudio = chooseAudio(audioStreams.filter { it.format == NewPipeFormat.M4A }, wish) ?: pickedAudio
        if (pickedVideoOnly != null && m4aAudio != null &&
            (pickedMuxed == null || pickedVideoOnly.qualityPx() > pickedMuxed.qualityPx()) &&
            pickedVideoOnly.format == NewPipeFormat.MPEG_4 && m4aAudio.format == NewPipeFormat.M4A
        ) {
            val vTmp = File(outputDir, "$baseName.vtmp")
            val aTmp = File(outputDir, "$baseName.atmp")
            val outFile = uniqueFile(outputDir, baseName, "mp4")
            try {
                val estVideo = pickedVideoOnly.itagItem?.contentLength?.takeIf { it > 0 } ?: 0L
                val estAudio = m4aAudio.itagItem?.contentLength?.takeIf { it > 0 } ?: 0L
                val estTotal = if (estVideo > 0 && estAudio > 0) estVideo + estAudio else 0L

                downloadToFile(pickedVideoOnly.content, vTmp, isCancelled) { done, total ->
                    val frac = if (estTotal > 0) (done.toFloat() / estTotal) else if (total > 0) (done.toFloat() / total) * 0.8f else 0f
                    onProgress(frac.coerceIn(0f, 0.8f), done, if (estTotal > 0) estTotal else total, "Downloading video")
                }
                downloadToFile(m4aAudio.content, aTmp, isCancelled) { done, total ->
                    val base = vTmp.length()
                    val combinedTotal = base + total
                    val frac = if (combinedTotal > 0) ((base + done).toFloat() / combinedTotal) else 0.85f
                    onProgress(frac.coerceIn(0.8f, 0.96f), base + done, combinedTotal, "Downloading audio")
                }
                onProgress(0.97f, vTmp.length() + aTmp.length(), vTmp.length() + aTmp.length(), "Muxing video and audio")
                muxMp4(vTmp, aTmp, outFile)
                onProgress(1f, outFile.length(), outFile.length(), "Saved")
                return outFile
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                outFile.delete()
                // Fall back to muxed stream below if MediaMuxer fails for an unsupported codec (e.g. AV1 on older Android).
                if (pickedMuxed == null) throw e
            } finally {
                vTmp.delete()
                aTmp.delete()
            }
        }

        val chosenMuxed = pickedMuxed
            ?: error("No direct video stream available")
        val ext = chosenMuxed.format?.suffix?.takeIf { it.isNotBlank() } ?: "mp4"
        val outFile = uniqueFile(outputDir, baseName, ext)
        val partFile = File(outputDir, "$baseName.$ext.part")
        try {
            downloadToFile(chosenMuxed.content, partFile, isCancelled) { done, total ->
                val frac = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                onProgress(frac, done, total, "Downloading video")
            }
            if (!partFile.renameTo(outFile)) {
                partFile.copyTo(outFile, overwrite = true)
                partFile.delete()
            }
            return outFile
        } finally {
            partFile.delete()
        }
    }

    private fun pickBestVideo(streams: List<VideoStream>, maxHeight: Int, preferMp4: Boolean): VideoStream? {
        val comparator = compareBy<VideoStream>(
            { it.qualityPx() },
            { !preferMp4 || it.format == NewPipeFormat.MPEG_4 }
        )
        return streams.filter { it.qualityPx() <= maxHeight }.maxWithOrNull(comparator)
            ?: streams.minByOrNull { it.qualityPx() }
    }

    private fun chooseAudio(streams: List<AudioStream>, wish: AudioWish): AudioStream? {
        val order = compareBy<AudioStream>(
            { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL },
            { it.format == NewPipeFormat.M4A },
            { it.averageBitrate }
        )
        if (!wish.language.isNullOrBlank()) {
            val wanted = streams.filter {
                it.audioLocale?.toLanguageTag()?.startsWith(wish.language, ignoreCase = true) == true
            }.maxWithOrNull(order)
            if (wanted != null) return wanted
        }
        return streams.maxWithOrNull(order)
    }

    private fun downloadToFile(
        streamUrl: String,
        target: File,
        isCancelled: () -> Boolean,
        onBytes: (Long, Long) -> Unit
    ) {
        if (isCancelled()) throw CancellationException("Cancelled")
        val reqBuilder = Request.Builder().url(streamUrl)
        NewPipeStreams.headersFor(streamUrl).forEach { (k, v) -> reqBuilder.header(k, v) }
        val call: Call = client.newCall(reqBuilder.build())
        call.execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} downloading stream")
            val body = response.body
            val total = body.contentLength()
            body.byteStream().use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var done = 0L
                    while (true) {
                        if (isCancelled()) {
                            call.cancel()
                            throw CancellationException("Cancelled")
                        }
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        done += read
                        onBytes(done, total)
                    }
                }
            }
        }
    }

    private fun muxMp4(videoFile: File, audioFile: File, outputFile: File) {
        val vExtractor = MediaExtractor()
        val aExtractor = MediaExtractor()
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            vExtractor.setDataSource(videoFile.absolutePath)
            aExtractor.setDataSource(audioFile.absolutePath)
            val vSrcTrack = selectTrack(vExtractor, "video/") ?: error("No video track found")
            val aSrcTrack = selectTrack(aExtractor, "audio/") ?: error("No audio track found")
            vExtractor.selectTrack(vSrcTrack)
            aExtractor.selectTrack(aSrcTrack)

            val vOutTrack = muxer.addTrack(vExtractor.getTrackFormat(vSrcTrack))
            val aOutTrack = muxer.addTrack(aExtractor.getTrackFormat(aSrcTrack))
            muxer.start()
            started = true

            val buffer = ByteBuffer.allocate(256 * 1024)
            val info = MediaCodec.BufferInfo()
            copySamples(vExtractor, muxer, vOutTrack, buffer, info)
            copySamples(aExtractor, muxer, aOutTrack, buffer, info)
        } finally {
            runCatching { if (started) muxer.stop() }
            runCatching { muxer.release() }
            runCatching { vExtractor.release() }
            runCatching { aExtractor.release() }
        }
    }

    private fun selectTrack(extractor: MediaExtractor, mimePrefix: String): Int? {
        for (i in 0 until extractor.trackCount) {
            val fmt: AndroidMediaFormat = extractor.getTrackFormat(i)
            val mime = fmt.getString(AndroidMediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return i
        }
        return null
    }

    private fun copySamples(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        trackIndex: Int,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo
    ) {
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(trackIndex, buffer, info)
            extractor.advance()
        }
    }

    internal fun safeFileName(raw: String): String {
        val cleaned = raw.trim()
            .replace(Regex("""[/\\:*?"<>|\r\n\t]+"""), "_")
            .trim('.', '_', ' ')
        return cleaned.take(100).ifBlank { "media_download" }
    }

    private fun uniqueFile(dir: File, base: String, ext: String): File {
        var candidate = File(dir, "$base.$ext")
        var idx = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($idx).$ext")
            idx++
        }
        return candidate
    }

    private fun Stream.isPlainHttp(): Boolean =
        deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && isUrl && !content.isNullOrBlank()

    private fun VideoStream.qualityPx(): Int =
        if (height > 0 && width > 0) minOf(height, width) else height
}
