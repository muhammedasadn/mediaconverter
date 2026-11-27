package com.muhammedasadn.mediaconverter.download.extractor.newpipe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class NewPipeDirectDownloaderTest {

    @Test
    fun safeFileNameStripsUnsafeAndLeadingDotChars() {
        val name = NewPipeDirectDownloader.safeFileName("..My: Video/Title*? \"2026\"   ")
        assertFalse(name.startsWith("."))
        assertFalse('/' in name || ':' in name || '*' in name || '?' in name || '"' in name)
        assertEquals("My_ Video_Title_ _2026", name)
    }

    @Test
    fun safeFileNameFallsBackWhenBlank() {
        assertEquals("media_download", NewPipeDirectDownloader.safeFileName("...///   "))
    }
}
