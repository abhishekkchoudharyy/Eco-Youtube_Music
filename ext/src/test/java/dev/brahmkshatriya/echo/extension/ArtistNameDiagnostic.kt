package dev.brahmkshatriya.echo.extension

import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.model.external.mediaitem.YtmSong
import sh.syk.kmpresources.library.model.Locale
import org.junit.Test

/**
 * Live diagnostic: prints how ytm-kt parses artist names for search results,
 * album tracks and song loads. Run with:
 *   ./gradlew :ext:test -PincludeDiagnostics --tests "*ArtistNameDiagnostic"
 */
class ArtistNameDiagnostic {

    private fun show(label: String, song: YtmSong) {
        val artists = song.artists.orEmpty().joinToString { "${it.name}(id=${it.id})" }
        println("DIAG $label: name=${song.name} | artists=[$artists]")
    }

    @Test
    fun diagnostic() = kotlinx.coroutines.runBlocking {
        val api = YoutubeiApi(dataLocale = Locale.parse("en-GB"))

        // 0. Direct song load (player path) - Shape of You
        runCatching {
            show("loadSong", api.LoadSong.loadSong("JGwWNGJdvx8").getOrThrow())
        }.onFailure { println("DIAG loadSong failed: ${it.message}") }

        // 1. Search songs
        runCatching {
            val results = api.Search.search("Shape of You Ed Sheeran").getOrThrow()
            val songs = results.categories.flatMap { it.first.items }.filterIsInstance<YtmSong>()
            songs.take(3).forEachIndexed { i, s -> show("search#$i", s) }
            songs.firstOrNull()?.id
        }.onFailure { println("DIAG search failed: ${it.message}") }.getOrNull()?.let { videoId ->

            // 2. Load song (same path as player)
            runCatching {
                show("loadSong", api.LoadSong.loadSong(videoId).getOrThrow())
            }.onFailure { println("DIAG loadSong failed: ${it.message}") }
        }

        // 3. Album tracks (same path as album view) — a well-known public album
        runCatching {
            val albumResults = api.Search.search("Divide Ed Sheeran", SearchTypeAlbumParams).getOrThrow()
            val album = albumResults.categories.flatMap { it.first.items }
                .filterIsInstance<dev.toastbits.ytmkt.model.external.mediaitem.YtmPlaylist>()
                .firstOrNull()
            println("DIAG album found: ${album?.name} id=${album?.id}")
            album?.id?.let { albumId ->
                val loaded = api.LoadPlaylist.loadPlaylist(albumId, null).getOrThrow()
                println("DIAG album artists=[${loaded.artists.orEmpty().joinToString { it.name ?: "NULL" }}]")
                loaded.items.orEmpty().take(3).forEachIndexed { i, item ->
                    (item as? YtmSong)?.let { show("albumTrack#$i", it) }
                }
            }
        }.onFailure { println("DIAG album failed: ${it.message}") }

        Unit
    }

    companion object {
        // params for ALBUM search type (default album filter)
        private const val SearchTypeAlbumParams = "EgWKAQIYAUICCAFqDBAOEAoQAxAEEAkQBQ%3D%3D"
    }
}
