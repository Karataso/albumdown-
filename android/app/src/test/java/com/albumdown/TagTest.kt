package com.albumdown

import com.albumdown.dl.embedUslt
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * embedUslt debe producir un frame USLT real del ID3v2. El bug de oct-2026:
 * `-metadata lyrics=` de ffmpeg solo genera un TXXX privado (desc='lyrics' o
 * 'USLT') que ningún player lee, y por eso "no había letras" en el celular
 * aunque LRCLIB respondía bien.
 *
 * Fixture: 3 frames MPEG1 Layer III sintéticos (128kbps/44100) — sin recursos
 * binarios en el repo y sin ffmpeg en el classpath de tests.
 */
class TagTest {

    private fun miniMp3(): java.io.File {
        val f = java.io.File.createTempFile("albumdown-mini", ".mp3")
        val bytes = ByteArray(417 * 3) // 3 frames de 417 B (sin padding)
        for (k in 0 until 3) {
            bytes[k * 417] = 0xFF.toByte()      // sync
            bytes[k * 417 + 1] = 0xFB.toByte()  // MPEG1, Layer III
            bytes[k * 417 + 2] = 0x90.toByte()  // 128 kbps, 44100 Hz
            bytes[k * 417 + 3] = 0x64.toByte()  // joint stereo
        }
        f.writeBytes(bytes)
        return f
    }

    /** Apariciones del identificador de frame, como bytes ASCII. */
    private fun ByteArray.seqCount(s: String): Int {
        var n = 0
        outer@ for (i in 0..size - s.length) {
            for (j in s.indices) if (this[i + j] != s[j].code.toByte()) continue@outer
            n++
        }
        return n
    }

    @Test
    fun `embedUslt escribe un frame USLT real con el texto exacto`() {
        val f = miniMp3()
        assertTrue("embedUslt devolvió false", embedUslt(f, "línea uno áéí\nlínea dos"))

        assertTrue(
            "el ID3 no contiene un frame USLT",
            f.readBytes().seqCount("USLT") > 0,
        )

        val readBack = AudioFileIO.read(f).tag?.getFirst(FieldKey.LYRICS)
        assertEquals("línea uno áéí\nlínea dos", readBack)
        f.delete()
    }

    @Test
    fun `el stream de audio queda intacto`() {
        val f = miniMp3()
        val syncsBefore = f.readBytes().seqCount("\uFFFB") // sync words reales
        assertTrue(embedUslt(f, "x"))
        assertEquals(
            "jaudiotagger tocó el audio",
            syncsBefore,
            f.readBytes().seqCount("\uFFFB"),
        )
        f.delete()
    }
}
