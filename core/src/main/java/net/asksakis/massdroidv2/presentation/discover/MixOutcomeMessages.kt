package net.asksakis.massdroidv2.presentation.discover

import net.asksakis.massdroidv2.domain.discover.MixLauncher
import net.asksakis.massdroidv2.domain.discover.MixLauncher.Mix
import net.asksakis.massdroidv2.domain.recommendation.MixPlaybackOrchestrator.MixResult

/**
 * The message a screen shows when a mix it started has finished, or null when the outcome needs
 * none (a server radio that started, a press ignored while another mix was building). The phone
 * and the TV share no UI module, so the wording lives here, beside the outcome it describes,
 * and the domain itself stays free of text.
 */
fun MixLauncher.Outcome.userMessage(): String? = when (mix) {
    Mix.SmartMix -> smartMixMessage(result)
    is Mix.GenreRadio -> genreRadioMessage(result, mix.genre)
}

/**
 * The message for an outcome that played nothing, or null when the mix played. For a surface
 * that reports only failures, such as the car, where a mix that plays speaks for itself.
 */
fun MixLauncher.Outcome.failureMessage(): String? = when (result) {
    is MixResult.Played, MixResult.Started, MixResult.Busy -> null
    else -> userMessage()
}

private fun smartMixMessage(result: MixResult): String? = when (result) {
    is MixResult.Played -> result.genre?.let { genre -> "${genre.capitalized()} mix ready (${result.count} tracks)" }
        ?: "Smart mix ready (${result.count} tracks)"
    MixResult.NotEnoughData -> "Not enough listening data for a solid mix yet"
    MixResult.NoPlayer -> "Select a player first"
    is MixResult.Failed -> result.message
    else -> null
}

private fun genreRadioMessage(result: MixResult, genre: String): String? = when (result) {
    is MixResult.Played -> "${genre.capitalized()} radio ready (${result.count} tracks)"
    MixResult.NoMatchingArtists -> "Not enough matching artists to start $genre radio"
    MixResult.NoPlayer -> "Select a player first"
    MixResult.StartTimeout -> "$genre radio did not start in time"
    is MixResult.Failed -> "Failed to start $genre radio"
    else -> null
}

private fun String.capitalized(): String = replaceFirstChar { it.uppercase() }
