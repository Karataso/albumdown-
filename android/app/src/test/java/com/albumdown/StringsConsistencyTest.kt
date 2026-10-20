package com.albumdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guardarraíles de i18n: values/ (inglés, por defecto) y values-es/ deben tener
 * exactamente las mismas llaves. Si alguien agrega un string y olvida traducirlo,
 * la app entera no truena — solo ese renglón cae al inglés silenciosamente.
 * Esto lo convierte en un error de build.
 */
class StringsConsistencyTest {

    private fun localeFile(name: String): File {
        // Los tests corren con CWD = módulo android/, no la raíz del repo.
        val candidates = listOf(
            File("app/src/main/res/$name/strings.xml"),
            File("src/main/res/$name/strings.xml"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("no encontré $name/strings.xml — busqué en: ${candidates.map { it.absolutePath }}")
    }

    private fun keysOf(file: File): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val names = mutableSetOf<String>()
        listOf("string", "plurals").forEach { tag ->
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                names += nodes.item(i).attributes.getNamedItem("name").nodeValue
            }
        }
        return names
    }

    @Test
    fun spanish_translates_every_english_string() {
        val en = keysOf(localeFile("values"))
        val es = keysOf(localeFile("values-es"))
        assertTrue("values/strings.xml vacío", en.isNotEmpty())
        assertEquals(
            "llaves que existen en un idioma y no en el otro",
            en, es,
        )
    }

    @Test
    fun every_plural_has_one_and_other() {
        // Un plural sin quantity="other" revienta en runtime al inflar.
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(localeFile("values"))
        val plurals = doc.getElementsByTagName("plurals")
        for (i in 0 until plurals.length) {
            val node = plurals.item(i)
            val quantities = node.childNodes.let { n ->
                (0 until n.length).map { n.item(it).attributes?.getNamedItem("quantity")?.nodeValue }
            }.filterNotNull()
            val name = node.attributes.getNamedItem("name").nodeValue
            assertTrue("plurals/$name sin other", "other" in quantities)
        }
    }

    @Test
    fun failure_messages_mention_the_symptom() {
        // El texto clave de block es el 403: es lo que el usuario puede googlear.
        val en = File(localeFile("values").path).readText()
        val es = File(localeFile("values-es").path).readText()
        assertTrue("err_blocked (en) sin 403", "403" in en.substringAfter("err_blocked").substringBefore("</string>"))
        assertTrue("err_blocked (es) sin 403", "403" in es.substringAfter("err_blocked").substringBefore("</string>"))
    }
}
