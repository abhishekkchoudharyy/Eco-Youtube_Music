package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.endpoints.EchoEnhancedSongEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoSongEndPoint
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import sh.syk.kmpresources.library.model.Locale
import org.junit.Test

/**
 * Reproduces the full playback flow: shelf-like track -> loadEnhancedTrack.
 * Run: ./gradlew :ext:test -PincludeDiagnostics --tests "*PlaybackFlowDiagnostic"
 */
class PlaybackFlowDiagnostic {

    private fun show(label: String, track: Track?) {
        if (track == null) { println("DIAG $label: NULL"); return }
        val artists = track.artists.joinToString { "${it.name}(id=${it.id})" }
        println("DIAG $label: title=${track.title} | artists=[$artists]")
    }

    @Test
    fun diagnostic() = kotlinx.coroutines.runBlocking {
        val api = YoutubeiApi(dataLocale = Locale.parse("en-GB"))
        val echoSongEndpoint = EchoSongEndPoint(api)
        val enhanced = EchoEnhancedSongEndpoint(api, echoSongEndpoint)

        // Simulated "shelf track" with CORRECT names (what the UI shows)
        val shelfTrack = Track(
            id = "JGwWNGJdvx8",
            title = "Shape of You",
            artists = listOf(dev.brahmkshatriya.echo.common.models.Artist("UC0C-w0YjGpqDXGB8IHb662A", "Ed Sheeran"))
        )

        // 1. What legacy parses
        runCatching {
            show("legacy.loadSong", echoSongEndpoint.loadSong("JGwWNGJdvx8").getOrThrow())
        }.onFailure { println("DIAG legacy failed: ${it.message}") }

        // 2. The full enhanced flow (same as app's loadTrack during playback)
        runCatching {
            show("enhanced.loadEnhancedTrack", enhanced.loadEnhancedTrack(
                "JGwWNGJdvx8", shelfTrack,
                dev.toastbits.ytmkt.model.external.ThumbnailProvider.Quality.HIGH
            ))
        }.onFailure { println("DIAG enhanced failed: ${it.message}") }

        Unit
    }
}
