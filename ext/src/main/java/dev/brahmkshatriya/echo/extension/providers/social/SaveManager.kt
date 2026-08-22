package dev.brahmkshatriya.echo.extension.providers.social

import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed.Companion.loadAll
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.auth.YouTubeAuthManager
import dev.brahmkshatriya.echo.extension.endpoints.EchoPlaylistEndpoint
import dev.brahmkshatriya.echo.extension.toTrack
import dev.toastbits.ytmkt.model.external.ThumbnailProvider
import dev.toastbits.ytmkt.model.external.SongLikedStatus

/**
 * Implements Echo's SaveClient ("Save to library").
 *
 * YouTube Music has no dedicated "save album" API: saving an album likes every
 * track on it (which also stores the album in the account's library). So:
 *  - Song  -> SetSongLiked (same as liking)
 *  - Album -> load all tracks, then SetSongLiked for each track id
 */
class SaveManager(
    private val authManager: YouTubeAuthManager,
    private val playlistEndpoint: EchoPlaylistEndpoint,
    private val thumbnailQuality: ThumbnailProvider.Quality
) {

    suspend fun isSaved(item: EchoMediaItem): Boolean {
        val auth = authManager.requireAuth()
        return when (item) {
            is Album -> {
                val likedIds = runCatching {
                    auth.LikedAlbums.getLikedAlbums().getOrNull().orEmpty().map { it.id }
                }.getOrNull().orEmpty()
                likedIds.any { it == item.id || it.removePrefix("VL") == item.id }
            }
            is Track -> {
                val status = runCatching {
                    auth.SongLiked.getSongLiked(item.id).getOrNull()
                }.getOrNull()
                // Trust the remote status when available; the cached extra is only
                // a fallback so a stale "isLiked" cannot override a fresh NEUTRAL.
                status?.let { it == SongLikedStatus.LIKED }
                    ?: (item.extras["isLiked"]?.toBoolean() == true)
            }
            else -> false
        }
    }

    suspend fun saveToLibrary(item: EchoMediaItem, shouldSave: Boolean) {
        val auth = authManager.requireAuth()
        val likeStatus = if (shouldSave) SongLikedStatus.LIKED else SongLikedStatus.NEUTRAL

        when (item) {
            is Track -> {
                auth.SetSongLiked.setSongLiked(item.id, likeStatus).getOrThrow()
            }
            is Album -> {
                val (_, _, tracksData) = playlistEndpoint.loadFromPlaylist(item.id, null, thumbnailQuality)
                val tracks = tracksData.toFeed().loadAll()
                val failed = mutableListOf<String>()
                tracks.forEach { track ->
                    runCatching {
                        auth.SetSongLiked.setSongLiked(track.id, likeStatus).getOrThrow()
                    }.onFailure { failed.add(track.title) }
                }
                if (failed.isNotEmpty()) {
                    throw Exception("Failed to update album tracks: ${failed.joinToString()}")
                }
            }
            else -> throw Exception("Saving ${item::class.simpleName} is not supported")
        }
    }
}
