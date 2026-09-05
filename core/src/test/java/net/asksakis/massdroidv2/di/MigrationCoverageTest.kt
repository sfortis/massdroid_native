package net.asksakis.massdroidv2.di

import androidx.room.migration.Migration
import net.asksakis.massdroidv2.data.database.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the one property of the migration set that cannot be seen by reading it:
 * that no installed version is stranded.
 *
 * Room falls back to a destructive migration whenever it cannot find a path, so a
 * missing step costs a user their listening history rather than failing the build.
 * That is what happened to schemas 11 to 14. They reached `origin/dev` and CI
 * published a debug APK for each, then their migrations were deleted once a single
 * v10 to v17 hop replaced them, and nothing noticed for five weeks.
 */
class MigrationCoverageTest {

    /**
     * Whether [from] can reach [to] through [migrations], resolving the way Room
     * does: take the largest jump available and fall back to shorter ones when it
     * leads nowhere.
     */
    private fun reaches(migrations: List<Migration>, from: Int, to: Int): Boolean {
        if (from == to) return true
        return migrations
            .filter { it.startVersion == from && it.endVersion <= to }
            .sortedByDescending { it.endVersion }
            .any { reaches(migrations, it.endVersion, to) }
    }

    private fun strandedVersions(migrations: List<Migration>): List<Int> {
        val target = AppDatabase.SCHEMA_VERSION
        return (AppModule.OLDEST_SHIPPED_SCHEMA until target)
            .filterNot { reaches(migrations, it, target) }
    }

    @Test
    fun `every schema that ever shipped reaches the current one`() {
        assertEquals(
            "schema versions with no migration path to ${AppDatabase.SCHEMA_VERSION}",
            emptyList<Int>(),
            strandedVersions(AppModule.ALL_MIGRATIONS.toList())
        )
    }

    /**
     * Proves the check above can actually fail. Removing the 13 to 14 step should
     * strand exactly the versions that depended on it, and nothing else: 11 and 12
     * reach 13 and stop there, while 10 is unaffected because it jumps straight to 17.
     */
    @Test
    fun `removing one step strands exactly the versions behind it`() {
        val without13to14 = AppModule.ALL_MIGRATIONS
            .filterNot { it.startVersion == 13 && it.endVersion == 14 }
        assertEquals(listOf(11, 12, 13), strandedVersions(without13to14))
    }

    @Test
    fun `no migration targets a schema that does not exist`() {
        val overshooting = AppModule.ALL_MIGRATIONS
            .filter { it.endVersion > AppDatabase.SCHEMA_VERSION }
            .map { "${it.startVersion}->${it.endVersion}" }
        assertEquals(emptyList<String>(), overshooting)
    }

    @Test
    fun `no two migrations claim the same step`() {
        val duplicates = AppModule.ALL_MIGRATIONS
            .groupBy { "${it.startVersion}->${it.endVersion}" }
            .filterValues { it.size > 1 }
            .keys
        assertEquals(emptySet<String>(), duplicates)
    }
}
