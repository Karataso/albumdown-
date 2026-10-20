package com.albumdown

import com.albumdown.dl.artistCaseFrom
import com.albumdown.dl.rewriteArtistCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Port de _merge_artist_folder (albumdown:790-803) y _rewrite_entry_artist
 * (albumdown:620-635): una sola grafía de artista entre carpeta y tags, o los
 * players parten al artista en dos (el catálogo de YT cambia mayúsculas entre
 * álbumes).
 */
class ArtistCaseTest {

    @Test
    fun `adopta la carpeta existente sin importar mayusculas`() {
        val dirs = listOf("Make Them Suffer", "El Cuarteto de Nos", "DM DOKURO")
        assertEquals("El Cuarteto de Nos", artistCaseFrom(dirs, "El Cuarteto De Nos"))
    }

    @Test
    fun `sin variante coincide devuelve null — se crea con la grafia de metadata`() {
        assertNull(artistCaseFrom(listOf("Make Them Suffer"), "Bring Me The Horizon"))
    }

    @Test
    fun `con dos variantes gana la primera ordenada — sorted del CLI`() {
        val dirs = listOf("bring me the horizon", "Bring Me The Horizon")
        assertEquals("Bring Me The Horizon", artistCaseFrom(dirs, "BRING ME THE HORIZON"))
    }

    @Test
    fun `lista vacia — crea nada y no truena`() {
        assertNull(artistCaseFrom(emptyList(), "Anyone"))
    }

    @Test
    fun `la tag del track adopta la canonical solo si es variante`() {
        assertEquals("El Cuarteto de Nos", rewriteArtistCase("el cuarteto de nos", "El Cuarteto de Nos"))
    }

    @Test
    fun `conserva la cola de feat tras la coma`() {
        assertEquals(
            "El Cuarteto de Nos, feat. X",
            rewriteArtistCase("el cuarteto de nos, feat. X", "El Cuarteto de Nos"),
        )
    }

    @Test
    fun `artistas distintos no se tocan`() {
        assertEquals("Bring Me The Horizon", rewriteArtistCase("Bring Me The Horizon", "Other Artist"))
    }

    @Test
    fun `vacio adopta la canonical — como el fallback ifEmpty del worker`() {
        assertEquals("Some Artist", rewriteArtistCase("", "Some Artist"))
    }
}
