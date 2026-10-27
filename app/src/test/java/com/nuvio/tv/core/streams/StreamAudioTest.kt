package com.nuvio.tv.core.streams

import com.nuvio.tv.core.streams.AudioLanguage.ENGLISH
import com.nuvio.tv.core.streams.AudioLanguage.FRENCH
import com.nuvio.tv.core.streams.AudioLanguage.HINDI
import com.nuvio.tv.core.streams.AudioLanguage.ITALIAN
import com.nuvio.tv.core.streams.AudioLanguage.JAPANESE
import com.nuvio.tv.core.streams.AudioLanguage.KOREAN
import com.nuvio.tv.core.streams.AudioLanguage.LATIN_SPANISH
import com.nuvio.tv.core.streams.AudioLanguage.PORTUGUESE
import com.nuvio.tv.core.streams.AudioLanguage.RUSSIAN
import com.nuvio.tv.core.streams.AudioLanguage.SPANISH
import com.nuvio.tv.core.streams.AudioLanguage.TAMIL
import com.nuvio.tv.core.streams.AudioLanguage.TELUGU
import com.nuvio.tv.core.streams.AudioLanguage.UKRAINIAN
import com.nuvio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamAudioTest {

    private var counter = 0

    private fun stream(addon: String, name: String?, title: String? = null, url: String? = "https://$addon.example/${counter++}") =
        Stream(
            name = name,
            title = title,
            description = null,
            url = url,
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = null,
            addonName = addon,
            addonLogo = null
        )

    private fun langs(text: String) = StreamAudioParser.parseText(text).languages

    @Test
    fun `names, release codes and flags`() {
        assertEquals(setOf(ENGLISH, JAPANESE), langs("[SubsPlease] Frieren 1080p Dual Audio ENG JPN"))
        assertEquals(setOf(ITALIAN, ENGLISH), langs("Movie.2023.iTA.ENG.1080p.BluRay.x264-MeM"))
        assertEquals(setOf(RUSSIAN, UKRAINIAN), langs("💾 4.2 GB 👤 12 ⚙️ RuTracker\n🇷🇺 / 🇺🇦"))
        assertEquals(setOf(ENGLISH, JAPANESE), langs("🗣️ English | Japanese"))
        assertEquals(setOf(ENGLISH, JAPANESE), langs("🌐 EN • JA"))
        assertEquals(setOf(HINDI, ENGLISH), langs("Pathaan (2023) Hindi + English 1080p"))
        assertEquals(setOf(FRENCH), langs("Les.Miserables.2012.TRUEFRENCH.1080p"))
        assertEquals(setOf(PORTUGUESE), langs("Filme 2024 1080p PT-BR"))
        assertEquals(setOf(KOREAN), langs("Parasite 2019 KOR 1080p"))
    }

    @Test
    fun `words that are titles or subtitles don't count`() {
        // Lowercase or Title-case codes are words, not tags.
        assertEquals(emptySet<AudioLanguage>(), langs("Cast.Away.2000.1080p.BluRay"))
        assertEquals(emptySet<AudioLanguage>(), langs("Ben 10 Alien Force S01E01"))
        // Subtitle languages.
        assertEquals(setOf(JAPANESE), langs("Japanese audio, English subs"))
        assertEquals(emptySet<AudioLanguage>(), langs("1080p WEB-DL ESub"))
        assertEquals(setOf(KOREAN), langs("Korean 1080p Subs: English, Spanish"))
        assertEquals(emptySet<AudioLanguage>(), langs("💬 English | French | German"))
        // Two-letter codes only on an audio line.
        assertEquals(emptySet<AudioLanguage>(), langs("IT 2017 1080p"))
    }

    @Test
    fun `title words are ignored`() {
        val s = stream("A", "Johnny.English.2003.1080p.BluRay")
        assertEquals(emptySet<AudioLanguage>(), StreamAudioParser.tagsWithoutTitles(s, listOf("Johnny English")).languages)
        val dub = stream("A", "The.French.Dispatch.2021.1080p.FRENCH")
        assertEquals(setOf(FRENCH), StreamAudioParser.tagsWithoutTitles(dub, listOf("The French Dispatch")).languages)
    }

    @Test
    fun `latino and indian regional languages`() {
        assertEquals(setOf(LATIN_SPANISH), langs("Coco 2017 Spanish Latino 1080p"))
        assertEquals(setOf(SPANISH, LATIN_SPANISH), langs("Coco 2017 Castellano + Latino"))
        assertEquals(setOf(TAMIL, TELUGU), langs("🇮🇳 Tamil + Telugu"))
        assertEquals(setOf(HINDI), langs("🇮🇳 1080p"))
    }

    @Test
    fun `resolve follows release naming`() {
        val untagged = StreamAudioParser.resolve(StreamAudioParser.parseText("Squid.Game.S01E01.1080p.WEB"), KOREAN)
        assertEquals(setOf(KOREAN), untagged.languages)
        assertTrue(untagged.assumedOriginal)

        val dual = StreamAudioParser.resolve(StreamAudioParser.parseText("Frieren S01E01 Dual-Audio 1080p"), JAPANESE)
        assertEquals(setOf(JAPANESE, ENGLISH), dual.languages)
        assertFalse(dual.unknown)

        val dubbed = StreamAudioParser.resolve(StreamAudioParser.parseText("Naruto 001 DUBBED 720p"), JAPANESE)
        assertEquals(setOf(ENGLISH), dubbed.languages)

        val multi = StreamAudioParser.resolve(StreamAudioParser.parseText("Dune.2021.MULTi.FRENCH.1080p"), ENGLISH)
        assertEquals(setOf(FRENCH, ENGLISH), multi.languages)

        val multiBare = StreamAudioParser.resolve(StreamAudioParser.parseText("Movie MULTi 1080p"), ENGLISH)
        assertTrue(multiBare.unknown)

        val vostfr = StreamAudioParser.resolve(StreamAudioParser.parseText("One Piece 1080p VOSTFR"), JAPANESE)
        assertEquals(setOf(JAPANESE), vostfr.languages)

        val noOriginal = StreamAudioParser.resolve(StreamAudioParser.parseText("Some.Movie.1080p"), null)
        assertTrue(noOriginal.unknown)
        assertTrue(noOriginal.languages.isEmpty())
    }

    @Test
    fun `track languages`() {
        assertEquals(JAPANESE, StreamAudioParser.trackLanguage("jpn", "Japanese"))
        assertEquals(ENGLISH, StreamAudioParser.trackLanguage("und", "English 5.1 (AC3)"))
        assertEquals(PORTUGUESE, StreamAudioParser.trackLanguage("pt-BR", null))
        assertEquals(LATIN_SPANISH, StreamAudioParser.trackLanguage("es-419", null))
        assertNull(StreamAudioParser.trackLanguage("und", "It's the director's commentary"))
        assertNull(StreamAudioParser.trackLanguage(null, "Track 2"))
    }

    @Test
    fun `menu lists file tracks and other sources by language`() {
        val current = stream("Showbox", "Frieren S01E01 1080p Dual Audio")
        val streams = listOf(
            current,
            stream("Torrentio", "Frieren S01E01 1080p [SubsPlease]"),
            stream("Torrentio", "Frieren S01E01 1080p ENG DUB"),
            stream("Comet", "🗣️ Korean | Japanese\n1080p"),
            stream("Other", "Frieren S01E01 1080p MULTi"),
            stream("Other", "Frieren S01E01 1080p iTA")
        )
        val tracks = listOf(
            AudioTrackRef(0, "jpn", "Japanese", "AAC · 2 ch"),
            AudioTrackRef(1, "eng", "English", "AAC · 2 ch"),
            AudioTrackRef(2, "ita", "Italian", "AAC · 2 ch")
        )
        val options = AudioLanguageMenu.buildOptions(
            streams = streams,
            isCurrentStream = { it === current },
            tracks = tracks,
            selectedTrackIndex = 0,
            original = JAPANESE,
            shownKeys = AudioLanguage.defaultMenuKeys,
            showUnknown = true
        )
        // Original (Japanese, playing) first, English in this file, Korean from Comet; Italian hidden.
        assertEquals(listOf(JAPANESE, ENGLISH, KOREAN), options.map { it.language })
        assertTrue(options[0].isOriginal && options[0].isCurrent && options[0].trackIndex == 0)
        assertEquals(1, options[1].trackIndex)
        assertEquals(null, options[2].trackIndex)
        assertEquals(1, options[2].sourceCount)

        val noUnknown = AudioLanguageMenu.buildOptions(
            streams, { it === current }, emptyList(), -1, null, AudioLanguage.defaultMenuKeys, showUnknown = false
        )
        assertTrue(noUnknown.none { it.language == null })
        val withUnknown = AudioLanguageMenu.buildOptions(
            streams, { it === current }, emptyList(), -1, null, AudioLanguage.defaultMenuKeys, showUnknown = true
        )
        assertTrue(withUnknown.last().language == null && withUnknown.last().sourceCount > 0)
    }

    @Test
    fun `picks the playing addon's source, then the closest quality`() {
        val current = stream("Torrentio", "Show 1080p")
        val streams = listOf(
            current,
            stream("Comet", "Show 1080p ENG DUB"),
            stream("Torrentio", "Show 2160p English dub"),
            stream("Torrentio", "Show 1080p English dub"),
        )
        val pick = AudioLanguageMenu.pickForLanguage(
            streams, ENGLISH, JAPANESE, preferredAddon = "Torrentio", currentQuality = StreamQuality.P1080,
            isCurrentStream = { it === current }
        )
        assertEquals(streams[3], pick)
    }

    @Test
    fun `sources naming another episode are skipped, this episode first`() {
        val current = stream("PenguPlay", "Show S01E05 1080p")
        val streams = listOf(
            current,
            stream("PenguPlay", "Show S01E01 1080p Hindi"),
            stream("PenguPlay", "Show Complete Season 1080p Hindi"),
            stream("Other", "Show.S01E05.720p.Hindi")
        )
        val ranked = AudioLanguageMenu.rankForLanguage(
            streams, HINDI, JAPANESE, preferredAddon = "PenguPlay", currentQuality = StreamQuality.P1080,
            isCurrentStream = { it === current }, season = 1, episode = 5
        )
        assertEquals(listOf(streams[3], streams[2]), ranked)
    }

    @Test
    fun `episode names and lengths`() {
        assertEquals(setOf(1 to 2), StreamEpisodeNames.named(stream("A", "Show.S01E02.1080p.x264")))
        assertEquals(setOf(3 to 12), StreamEpisodeNames.named(stream("A", "Show 3x12 1920x1080")))
        assertNull(StreamEpisodeNames.matches(stream("A", "Movie 2019 1080p"), 1, 2))
        assertTrue(AudioLanguageMenu.durationFits(24 * 60_000L, 23 * 60_000L))
        assertFalse(AudioLanguageMenu.durationFits(24 * 60_000L, 145 * 60_000L))
        assertTrue(AudioLanguageMenu.durationFits(0L, 145 * 60_000L))
        assertTrue(AudioLanguageMenu.durationFits(120 * 60_000L, 132 * 60_000L))
    }

    @Test
    fun `multi or dual subtitles say nothing about the audio`() {
        assertFalse(StreamAudioParser.parseText("Show.S01E01.1080p.MULTi-SUBS.WEB").multi)
        assertFalse(StreamAudioParser.parseText("Anime 1080p Multi-Subs").multi)
        assertFalse(StreamAudioParser.parseText("Anime Dual Subs 1080p").dual)
        assertTrue(StreamAudioParser.parseText("Movie MULTi 1080p").multi)
        assertTrue(StreamAudioParser.parseText("Frieren S01E01 Dual-Audio 1080p").dual)
    }

    @Test
    fun `release codes in a track title count only in capitals or on their own`() {
        assertNull(StreamAudioParser.trackLanguage(null, "Cast commentary"))
        assertNull(StreamAudioParser.trackLanguage("und", "Commentary with Ben"))
        assertEquals(ITALIAN, StreamAudioParser.trackLanguage(null, "ita"))
        assertEquals(ITALIAN, StreamAudioParser.trackLanguage(null, "ITA 5.1"))
    }

    @Test
    fun `cn is Mandarin and Spanish outside Spain is Latin American`() {
        assertEquals(AudioLanguage.CHINESE, AudioLanguage.fromCode("cn"))
        assertEquals(LATIN_SPANISH, AudioLanguage.fromCode("es-CL"))
        assertEquals(LATIN_SPANISH, AudioLanguage.fromCode("es_CO"))
        assertEquals(SPANISH, AudioLanguage.fromCode("es-ES"))
        assertEquals(SPANISH, AudioLanguage.fromCode("es"))
    }

    @Test
    fun `episode runs and codec text`() {
        assertEquals(setOf(1 to 1, 1 to 2, 1 to 3), StreamEpisodeNames.named(stream("A", "Show.S01E01-E03.1080p")))
        assertEquals(setOf(1 to 1, 1 to 2), StreamEpisodeNames.named(stream("A", "Show.S01E01E02.1080p")))
        assertEquals(setOf(2 to 4, 2 to 5), StreamEpisodeNames.named(stream("A", "Show S02E04-05 1080p")))
        assertEquals(setOf(1 to 5), StreamEpisodeNames.named(stream("A", "Show S01E05 AAC5.1x264")))
        assertEquals(setOf(1 to 1), StreamEpisodeNames.named(stream("A", "Show.S01E01-1080p")))
        assertTrue(StreamEpisodeNames.matches(stream("A", "Show.S01E01-E03.1080p"), 1, 2) == true)
    }

    @Test
    fun `a source tagged with the language ranks above an untagged one, whatever its quality`() {
        val tagged = stream("A", "Movie JPN")
        val untagged = stream("A", "Movie 1080p")
        val ranked = AudioLanguageMenu.rankForLanguage(
            listOf(untagged, tagged), JAPANESE, JAPANESE, null, StreamQuality.P1080, { false }
        )
        assertEquals(listOf(tagged, untagged), ranked)
    }

    @Test
    fun `title words in a release name are not language codes`() {
        assertEquals(emptySet<AudioLanguage>(), langs("BEN-HUR.1959.1080p.BluRay"))
        assertEquals(emptySet<AudioLanguage>(), langs("THE.FIN.2023.1080p"))
        assertEquals(emptySet<AudioLanguage>(), langs("MAL.2022.1080p"))
        // Tags after the year still count.
        assertEquals(setOf(AudioLanguage.ITALIAN, AudioLanguage.ENGLISH), langs("Movie.2020.ITA.ENG.1080p"))
    }

    @Test
    fun `a known title's code words are not languages`() {
        val benHur = stream("A", name = "BEN-HUR 1080p BluRay")
        assertEquals(emptySet<AudioLanguage>(), StreamAudioParser.tagsWithoutTitles(benHur, listOf("Ben-Hur")).languages)
    }

    @Test
    fun `codes count in any case on an audio line`() {
        assertEquals(setOf(AudioLanguage.ENGLISH, AudioLanguage.JAPANESE), langs("Movie [Dual Audio] [Eng-Jap]"))
    }

    @Test
    fun `languages in their own script`() {
        assertEquals(setOf(AudioLanguage.JAPANESE), langs("Movie 日本語"))
        assertEquals(setOf(AudioLanguage.KOREAN), langs("한국어"))
    }

    @Test
    fun `a track coded spa but named Latino is the Latin American dub`() {
        assertEquals(AudioLanguage.LATIN_SPANISH, StreamAudioParser.trackLanguage("spa", "Latino"))
        assertEquals(AudioLanguage.SPANISH, StreamAudioParser.trackLanguage("spa", "Castellano"))
        assertEquals(AudioLanguage.PORTUGUESE, StreamAudioParser.trackLanguage("pob", null))
    }

    @Test
    fun `a codec is not an episode and a spelled out episode is`() {
        assertEquals(emptySet<Pair<Int, Int>>(), StreamEpisodeNames.named(stream("A", name = "Movie AAC2x264")))
        assertEquals(setOf(1 to 2), StreamEpisodeNames.named(stream("A", name = "Show Season 1 Episode 2")))
    }
}
