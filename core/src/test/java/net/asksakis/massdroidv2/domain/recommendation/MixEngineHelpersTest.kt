package net.asksakis.massdroidv2.domain.recommendation

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.domain.model.Track
import org.junit.Test
import kotlin.random.Random

/** Pure-helper tests for MixEngine diversity/identity logic. */
class MixEngineHelpersTest {

    private val engine = MixEngine()

    private fun track(name: String, artist: String, uri: String = "lib://$name") =
        Track(itemId = name, provider = "p", name = name, uri = uri, artistNames = artist)

    // --- dynamicMaxPerArtist: ceil(target/usableArtists) coerced into [2, 3] ---

    @Test
    fun `dynamicMaxPerArtist stays at the floor when there are many artists`() {
        // ceil(50/30) = 2 -> floor
        assertThat(engine.dynamicMaxPerArtist(target = 50, usableArtists = 30)).isEqualTo(2)
    }

    @Test
    fun `dynamicMaxPerArtist rises toward the ceiling for a small artist pool`() {
        // ceil(50/10) = 5 -> coerced to ceiling 3
        assertThat(engine.dynamicMaxPerArtist(target = 50, usableArtists = 10)).isEqualTo(3)
        // ceil(100/1) = 100 -> ceiling 3
        assertThat(engine.dynamicMaxPerArtist(target = 100, usableArtists = 1)).isEqualTo(3)
    }

    @Test
    fun `dynamicMaxPerArtist never drops below the floor`() {
        // ceil(5/10) = 1 -> coerced up to floor 2
        assertThat(engine.dynamicMaxPerArtist(target = 5, usableArtists = 10)).isEqualTo(2)
    }

    @Test
    fun `dynamicMaxPerArtist returns the floor when no usable artists`() {
        assertThat(engine.dynamicMaxPerArtist(target = 20, usableArtists = 0)).isEqualTo(2)
        assertThat(engine.dynamicMaxPerArtist(target = 20, usableArtists = -3)).isEqualTo(2)
    }

    // --- trackDedupeKey: "artist|name" normalized, uri fallback when either blank ---

    @Test
    fun `trackDedupeKey builds a normalized artist-name key`() {
        assertThat(engine.trackDedupeKey(track("Song", "Artist"))).isEqualTo("artist|song")
        // first artist before comma; punctuation collapses to single spaces
        assertThat(engine.trackDedupeKey(track("  Song!!!  ", "Art-ist, Co.")))
            .isEqualTo("art ist|song")
    }

    @Test
    fun `trackDedupeKey falls back to uri when name or artist is blank`() {
        assertThat(engine.trackDedupeKey(track("", "Artist", uri = "spotify://t/1")))
            .isEqualTo("spotify://t/1")
        assertThat(engine.trackDedupeKey(track("Song", "", uri = "u9"))).isEqualTo("u9")
    }

    // --- canEmitArtist: first-pass uniqueness gate + recent-gap rule ---

    @Test
    fun `canEmitArtist allows a never-seen artist`() {
        assertThat(engine.canEmitArtist("a", emptyList(), emptyMap(), 20, 12)).isTrue()
    }

    @Test
    fun `canEmitArtist blocks a repeat before the first pass is complete`() {
        assertThat(engine.canEmitArtist("a", listOf("a"), mapOf("a" to 1), 20, 12)).isFalse()
    }

    @Test
    fun `canEmitArtist enforces the recent-artist gap after the first pass`() {
        // first pass complete (emitted size 3 >= 3); gap 2 -> last two are [c, d]
        assertThat(engine.canEmitArtist("a", listOf("b", "c", "d"), mapOf("a" to 1), 3, 2)).isTrue()
        // 'a' is within the last 2 emitted -> blocked
        assertThat(engine.canEmitArtist("a", listOf("b", "c", "a"), mapOf("a" to 1), 3, 2)).isFalse()
    }

    // --- weighted sampling (seeded Random -> deterministic) ---

    private val scores = mapOf("a" to 1.0, "b" to 2.0, "c" to 3.0)

    @Test
    fun `weightedSampleArtists returns all candidates when the limit covers the pool`() {
        val out = engine.weightedSampleArtists(listOf("a", "b", "c"), scores, limit = 5, random = Random(1))
        assertThat(out).containsExactly("a", "b", "c")
    }

    @Test
    fun `weightedSampleArtists respects the limit with distinct picks`() {
        val out = engine.weightedSampleArtists(listOf("a", "b", "c"), scores, limit = 2, random = Random(1))
        assertThat(out).hasSize(2)
        assertThat(out.toSet()).hasSize(2)
        assertThat(listOf("a", "b", "c")).containsAtLeastElementsIn(out)
    }

    @Test
    fun `weightedSampleArtists is deterministic for a fixed seed`() {
        val a = engine.weightedSampleArtists(listOf("a", "b", "c"), scores, 3, Random(42))
        val b = engine.weightedSampleArtists(listOf("a", "b", "c"), scores, 3, Random(42))
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `weightedSampleArtists returns empty for empty pool or non-positive limit`() {
        assertThat(engine.weightedSampleArtists(emptyList(), scores, 5, Random(1))).isEmpty()
        assertThat(engine.weightedSampleArtists(listOf("a"), scores, 0, Random(1))).isEmpty()
    }

    @Test
    fun `weightedOrderArtists returns a full deterministic permutation`() {
        val a = engine.weightedOrderArtists(listOf("a", "b", "c"), scores, Random(7))
        assertThat(a).containsExactly("a", "b", "c")
        val b = engine.weightedOrderArtists(listOf("a", "b", "c"), scores, Random(7))
        assertThat(a).isEqualTo(b)
        assertThat(engine.weightedOrderArtists(emptyList(), scores, Random(7))).isEmpty()
    }

    // --- score composition helpers ---

    @Test
    fun `compressPreferenceScore is a sign-preserving square root`() {
        assertThat(engine.compressPreferenceScore(0.0)).isEqualTo(0.0)
        assertThat(engine.compressPreferenceScore(100.0)).isWithin(1e-9).of(10.0)
        assertThat(engine.compressPreferenceScore(4.0)).isWithin(1e-9).of(2.0)
        assertThat(engine.compressPreferenceScore(-100.0)).isWithin(1e-9).of(-10.0)
    }

    @Test
    fun `daypartBonus is centered at 0_35 and clamped`() {
        assertThat(engine.daypartBonus(null)).isEqualTo(0.0)
        assertThat(engine.daypartBonus(0.35)).isWithin(1e-9).of(0.0)
        assertThat(engine.daypartBonus(0.5)).isWithin(1e-9).of(0.27)
        assertThat(engine.daypartBonus(1.0)).isWithin(1e-9).of(0.95)   // clamped from 1.17
        assertThat(engine.daypartBonus(0.0)).isWithin(1e-9).of(-0.45)  // clamped from -0.63
    }

    @Test
    fun `compositeArtistScore sums its weighted terms`() {
        val empty = emptyMap<String, Double>()
        // direct artist preference (compressed): sqrt(100) = 10
        assertThat(engine.compositeArtistScore("u", mapOf("u" to 100.0), empty, empty, empty, emptySet(), empty))
            .isWithin(1e-9).of(10.0)
        // BLL fallback when no direct preference: sqrt(4) = 2
        assertThat(engine.compositeArtistScore("u", empty, mapOf("u" to 4.0), empty, empty, emptySet(), empty))
            .isWithin(1e-9).of(2.0)
        // smart signal weighted 0.45
        assertThat(engine.compositeArtistScore("u", empty, empty, mapOf("u" to 10.0), empty, emptySet(), empty))
            .isWithin(1e-9).of(4.5)
        // favorite bonus only
        assertThat(engine.compositeArtistScore("u", empty, empty, empty, empty, setOf("u"), empty))
            .isWithin(1e-9).of(0.8)
    }
}
