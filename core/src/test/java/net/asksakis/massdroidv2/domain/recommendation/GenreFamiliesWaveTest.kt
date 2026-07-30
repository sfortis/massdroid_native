package net.asksakis.massdroidv2.domain.recommendation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the genres added on 2026-07-29, after measuring that the Last.fm whitelist
 * was discarding the strongest tag of 11.7% of a real library's artists.
 *
 * The synthwave scene is the headline case: "synthwave" was the single most-lost
 * lead tag (14 artists), and `familyOf` returned null for all of it because "wave"
 * is deliberately NOT a glued suffix rule (it spans new wave / darkwave /
 * synthwave, which live in three different families).
 */
class GenreFamiliesWaveTest {

    @Test
    fun `the synthwave scene is electronic`() {
        listOf("synthwave", "retrowave", "new retro wave", "dreamwave", "chillwave", "electroclash")
            .forEach { assertThat(dominantFamily(listOf(it))).isEqualTo("electronic") }
    }

    @Test
    fun `cold and minimal synth are goth, not synthwave`() {
        // Ash Code / NNHMN / Hante. territory, not The Midnight's.
        listOf("coldwave", "minimal synth", "minimal wave")
            .forEach { assertThat(dominantFamily(listOf(it))).isEqualTo("goth") }
    }

    @Test
    fun `the three wave families stay apart`() {
        // The reason "wave" is not a suffix rule.
        assertThat(dominantFamily(listOf("new wave"))).isEqualTo("rock")
        assertThat(dominantFamily(listOf("darkwave"))).isEqualTo("goth")
        assertThat(dominantFamily(listOf("synthwave"))).isEqualTo("electronic")
    }

    @Test
    fun `gaze genres split between rock and metal`() {
        // "gaze" cannot be a suffix rule: shoegaze is rock, blackgaze is metal.
        assertThat(dominantFamily(listOf("shoegaze"))).isEqualTo("rock")
        assertThat(dominantFamily(listOf("blackgaze"))).isEqualTo("metal")
        assertThat(dominantFamily(listOf("doomgaze"))).isEqualTo("metal")
    }

    @Test
    fun `genres the suffix chain already resolved keep resolving`() {
        // These only needed the whitelist to stop blocking them; familyOf already
        // handled them through its last-word lookup.
        assertThat(dominantFamily(listOf("minimal techno"))).isEqualTo("electronic")
        assertThat(dominantFamily(listOf("progressive house"))).isEqualTo("electronic")
        assertThat(dominantFamily(listOf("melodic techno"))).isEqualTo("electronic")
        assertThat(dominantFamily(listOf("chill house"))).isEqualTo("electronic")
        assertThat(dominantFamily(listOf("vocal trance"))).isEqualTo("electronic")
        assertThat(dominantFamily(listOf("chamber pop"))).isEqualTo("pop")
        assertThat(dominantFamily(listOf("neo progressive"))).isEqualTo("rock")
    }

    @Test
    fun `explicitly mapped oddities override the suffix guess`() {
        // "noise pop" is the Jesus and Mary Chain lineage, not pop.
        assertThat(dominantFamily(listOf("noise pop"))).isEqualTo("rock")
        // "psychill" is psychedelic ambient; the suffix chain reached nothing.
        assertThat(dominantFamily(listOf("psychill"))).isEqualTo("chill")
        assertThat(dominantFamily(listOf("bossa nova"))).isEqualTo("world")
    }

    @Test
    fun `a synthwave artist now reads as electronic instead of chill`() {
        // Hello Meteor, measured: tags were [ambient, electronic] because
        // "synthwave" and "chillwave" were both discarded, so the artist counted
        // as CHILL. With the scene mapped, the dominant tag decides correctly.
        assertThat(dominantFamily(listOf("synthwave", "chillwave", "ambient")))
            .isEqualTo("electronic")
        // Nina, measured: was [pop, synthpop] -> pop.
        assertThat(dominantFamily(listOf("synthwave", "pop", "retrowave")))
            .isEqualTo("electronic")
    }

    // --- dominantGenre: what the mix gets CALLED for the user ---

    @Test
    fun `the mix is named after the primary's strongest mapped tag`() {
        // Real cluster from the device: "post punk/indie pop/shoegaze -> rock".
        // "Post punk mix ready" is useful; "Rock mix ready" is not.
        assertThat(dominantGenre(listOf("post punk", "indie pop", "shoegaze")))
            .isEqualTo("post punk")
        assertThat(dominantGenre(listOf("ebm", "darkwave"))).isEqualTo("ebm")
    }

    @Test
    fun `leading mood and format tags are skipped, not used as the name`() {
        // "instrumental" and "soundtrack" are deliberately unmapped: they sort
        // first for some artists but say nothing about the genre.
        assertThat(dominantGenre(listOf("instrumental", "soundtrack", "techno")))
            .isEqualTo("techno")
    }

    @Test
    fun `an unnameable cluster returns null rather than a guess`() {
        assertThat(dominantGenre(listOf("instrumental", "seen live"))).isNull()
        assertThat(dominantGenre(emptyList())).isNull()
    }

    @Test
    fun `the name is normalized for display`() {
        assertThat(dominantGenre(listOf("Post-Punk"))).isEqualTo("post-punk")
        assertThat(dominantGenre(listOf("  Techno  "))).isEqualTo("techno")
    }

    @Test
    fun `newly allowed genres can name a mix`() {
        assertThat(dominantGenre(listOf("synthwave", "electronic"))).isEqualTo("synthwave")
        assertThat(dominantGenre(listOf("minimal techno", "techno"))).isEqualTo("minimal techno")
    }

    @Test
    fun `a lounge act tagged bossa nova is world, not chill`() {
        // Sarah Menescal, measured: was [lounge, jazz] -> chill.
        assertThat(dominantFamily(listOf("bossa nova", "lounge", "jazz"))).isEqualTo("world")
    }
}
