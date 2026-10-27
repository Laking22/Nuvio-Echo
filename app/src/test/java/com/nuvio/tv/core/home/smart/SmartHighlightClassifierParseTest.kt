package com.nuvio.tv.core.home.smart

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmartHighlightClassifierParseTest {

    private fun meta(id: String) = MetaPreview(
        id = id, type = ContentType.MOVIE, name = id, poster = null, posterShape = PosterShape.POSTER,
        background = "b", logo = null, description = null, releaseInfo = null, imdbRating = null, genres = emptyList()
    )

    private val heist = SmartHighlightCatalog.theme("heist_night")!!
    private val rows = listOf(heist to listOf(meta("a"), meta("b"), meta("c")))

    @Test
    fun `kept numbers map back to ids and bad entries are ignored`() {
        val text = """[{"theme":"heist_night","keep":[2,0,9,-1,2],"name":"Big Score Night"},{"theme":"made_up","keep":[0]}]"""
        val out = SmartHighlightClassifier.parse(text, rows, suggestNames = true)
        assertEquals(setOf("heist_night"), out.keys)
        assertEquals(listOf("c", "a"), out.getValue("heist_night").keep)
        assertEquals("Big Score Night", out.getValue("heist_night").name)
    }

    @Test
    fun `names are dropped when none were asked for`() {
        val text = """```json
            [{"theme":"heist_night","keep":[1],"name":"Mind Games"}]
        ```"""
        assertNull(SmartHighlightClassifier.parse(text, rows, suggestNames = false).getValue("heist_night").name)
    }
}
