package net.asksakis.massdroidv2.data.genre

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import net.asksakis.massdroidv2.data.database.GenreUsageRow
import net.asksakis.massdroidv2.data.database.PlayHistoryDao
import org.junit.Test

/**
 * Pins that a genre is written under the spelling the database already has for it.
 *
 * Schema 21 merged the stored spellings (synthpop, synth-pop and synth pop were
 * three genres on 2026-10-05). Without this resolver the next MusicBrainz answer
 * spelled "synth-pop" would have split the genre again.
 */
class GenreSpellingResolverTest {

    private val dao = mockk<PlayHistoryDao>()

    private fun resolverWith(vararg stored: Pair<String, Int>): GenreSpellingResolver {
        coEvery { dao.getGenreUsage() } returns stored.map { (name, uses) -> GenreUsageRow(name, uses) }
        return GenreSpellingResolver(dao)
    }

    @Test
    fun `an incoming spelling is stored under the existing one`() = runTest {
        val resolver = resolverWith("post punk" to 340)

        assertThat(resolver.spellingToStore("post-punk")).isEqualTo("post punk")
        assertThat(resolver.spellingToStore("Post-Punk")).isEqualTo("post punk")
        assertThat(resolver.spellingToStore("postpunk")).isEqualTo("post punk")
    }

    @Test
    fun `a new genre is stored as it arrives and later spellings follow it`() = runTest {
        val resolver = resolverWith("house" to 10)

        assertThat(resolver.spellingToStore("  Lo-Fi ")).isEqualTo("lo-fi")
        assertThat(resolver.spellingToStore("lo fi")).isEqualTo("lo-fi")
        assertThat(resolver.spellingToStore("house")).isEqualTo("house")
    }

    @Test
    fun `blank names are not stored`() = runTest {
        val resolver = resolverWith()

        assertThat(resolver.spellingToStore("   ")).isNull()
        assertThat(resolver.spellingsToStore(listOf("", "jazz", " "))).containsExactly("jazz")
    }

    @Test
    fun `a list yields one name per genre, in input order`() = runTest {
        val resolver = resolverWith("synthpop" to 1109)

        assertThat(resolver.spellingsToStore(listOf("synth-pop", "darkwave", "synth pop", "dark wave")))
            .containsExactly("synthpop", "darkwave").inOrder()
    }

    @Test
    fun `a lookup finds the stored spelling but does not decide future writes`() = runTest {
        val resolver = resolverWith("synthpop" to 1109)

        assertThat(resolver.spellingToQuery("synth-pop")).isEqualTo("synthpop")
        // Unknown: the normalized input, and NOT registered, so the first real
        // write still decides how the new genre is spelled.
        assertThat(resolver.spellingToQuery("Dream-Pop")).isEqualTo("dream-pop")
        assertThat(resolver.spellingToStore("dream pop")).isEqualTo("dream pop")
    }

    @Test
    fun `if a duplicate is ever stored the most used spelling wins`() = runTest {
        val resolver = resolverWith("synth-pop" to 316, "synthpop" to 1109, "synth pop" to 42)

        assertThat(resolver.spellingToStore("synth pop")).isEqualTo("synthpop")
    }

    @Test
    fun `the database is read once per process`() = runTest {
        val resolver = resolverWith("house" to 1)

        resolver.spellingToStore("house")
        resolver.spellingToQuery("techno")
        resolver.spellingsToStore(listOf("deep house", "acid house"))

        coVerify(exactly = 1) { dao.getGenreUsage() }
    }
}
