package net.asksakis.massdroidv2.domain.whatsnew

/**
 * The folder the build writes the release notes and their animations into, inside the
 * app's assets. It is the one thing the build and the app have to agree on, so it is
 * written here rather than twice.
 */
const val WHATS_NEW_ASSET_DIR = "whatsnew"

/** The notes themselves, under [WHATS_NEW_ASSET_DIR]. */
const val WHATS_NEW_NOTES_ASSET = "$WHATS_NEW_ASSET_DIR/notes.md"

/**
 * What kind of change a group of entries describes.
 *
 * The heading is matched against the headings in `WHATSNEW.md`, ignoring case, so the
 * release notes and the app show the same three groups. A heading the app does not know
 * still gets its own group, it just carries no kind; nothing written in the file is
 * dropped because the app failed to recognise it.
 */
enum class WhatsNewChange(val heading: String) {
    ADDED("Added"),
    IMPROVED("Improved"),
    FIXED("Fixed");

    companion object {
        fun fromHeading(heading: String): WhatsNewChange? =
            entries.firstOrNull { it.heading.equals(heading.trim(), ignoreCase = true) }
    }
}

/**
 * One line of the changelog, with the animation that belongs to it if it has one.
 *
 * [animation] is the file name written in the tag above the line, which the app opens
 * from its assets. It is a name and not a resource id because the whole point is that a
 * release can add an animation without anybody touching Kotlin.
 */
data class WhatsNewEntry(
    val text: String,
    val animation: String? = null
)

/**
 * One heading and the entries written under it.
 *
 * [change] is null for entries that sit under a heading the app does not know, and for
 * entries written before the first heading of the file.
 */
data class WhatsNewSection(
    val change: WhatsNewChange?,
    val heading: String?,
    val entries: List<WhatsNewEntry>
)

/** A release's news: the version it belongs to, and the sections in the order written. */
data class WhatsNewRelease(
    val versionName: String,
    val sections: List<WhatsNewSection>
) {
    val isEmpty: Boolean get() = sections.isEmpty()
}

/**
 * Reads `WHATSNEW.md` into something the app can show.
 *
 * The release notes file is the only place this text is written. It ships in the APK as
 * an asset (copied there by the build), so the sheet after an update needs no network and
 * always matches the version that is actually installed.
 *
 * What it understands is deliberately the small part of Markdown the file uses: ATX
 * headings, `-` or `*` list items, wrapped continuation lines, and one tag of our own for
 * an animation. Anything else is carried through as text rather than interpreted, because
 * this is a changelog and not a document.
 */
object WhatsNewParser {

    private val HEADING = Regex("""^#{1,6}\s+(.*)$""")
    private val BULLET = Regex("""^[-*]\s+(.*)$""")

    /**
     * The one tag of our own, written on its own line above the entry it belongs to.
     *
     * An HTML comment because GitHub renders the same file as the release notes and does
     * not show comments, so the tag is invisible to everybody except this parser.
     */
    private val ANIMATION = Regex("""^<!--\s*animation:\s*(\S+)\s*-->$""")

    /** Issue and pull request numbers, which mean nothing to somebody reading the app. */
    private val ISSUE_REF = Regex("""#\d+""")
    private val EMPTY_PARENS = Regex("""\(\s*\)""")
    private val LEADING_SEPARATOR = Regex("""\(\s*[,;]\s*""")
    private val REPEATED_SPACE = Regex("""\s{2,}""")
    private val SPACE_BEFORE_PUNCTUATION = Regex("""\s+([.,;:!?])""")

    @Suppress("NestedBlockDepth")
    fun parse(markdown: String, versionName: String): WhatsNewRelease {
        val sections = mutableListOf<WhatsNewSection>()
        var heading: String? = null
        var entries = mutableListOf<WhatsNewEntry>()
        var pendingAnimation: String? = null

        fun closeSection() {
            if (entries.isNotEmpty()) {
                sections += WhatsNewSection(
                    change = heading?.let { WhatsNewChange.fromHeading(it) },
                    heading = heading,
                    entries = entries.toList()
                )
            }
            entries = mutableListOf()
        }

        markdown.lineSequence().forEach { raw ->
            val line = raw.trim()
            val headingMatch = HEADING.matchEntire(line)
            val bulletMatch = BULLET.matchEntire(line)
            val animationMatch = ANIMATION.matchEntire(line)
            when {
                animationMatch != null -> pendingAnimation = animationMatch.groupValues[1]
                headingMatch != null -> {
                    closeSection()
                    heading = headingMatch.groupValues[1].trim()
                }
                bulletMatch != null -> {
                    entries += WhatsNewEntry(clean(bulletMatch.groupValues[1]), pendingAnimation)
                    pendingAnimation = null
                }
                // A wrapped line belongs to the entry above it; a stray line before any
                // entry is prose the changelog does not have, and is left out.
                line.isNotEmpty() && entries.isNotEmpty() -> {
                    val last = entries.last()
                    entries[entries.lastIndex] = last.copy(text = clean(last.text + " " + line))
                }
            }
        }
        closeSection()
        return WhatsNewRelease(versionName = versionName, sections = sections)
    }

    /**
     * An entry as the person using the app should read it.
     *
     * Issue numbers are stripped because they are maintainer bookkeeping, while a thank
     * you to a contributor is kept: `(#71, thanks @someone)` becomes `(thanks @someone)`
     * and a bare `(#77)` disappears along with the space in front of it.
     */
    private fun clean(entry: String): String = entry
        .replace(ISSUE_REF, "")
        .replace(LEADING_SEPARATOR, "(")
        .replace(EMPTY_PARENS, "")
        .replace(REPEATED_SPACE, " ")
        .replace(SPACE_BEFORE_PUNCTUATION, "$1")
        .trim()
}
