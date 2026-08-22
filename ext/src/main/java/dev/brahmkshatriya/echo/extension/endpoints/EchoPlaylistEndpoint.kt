package dev.brahmkshatriya.echo.extension.endpoints

import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.YoutubeExtension.Companion.SONGS
import dev.brahmkshatriya.echo.extension.toTrack
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiPostBody
import dev.toastbits.ytmkt.model.ApiEndpoint
import dev.toastbits.ytmkt.model.YtmApi
import dev.toastbits.ytmkt.model.external.ThumbnailProvider
import dev.toastbits.ytmkt.model.external.mediaitem.YtmPlaylist
import dev.toastbits.ytmkt.model.external.mediaitem.YtmPlaylistBuilder
import dev.toastbits.ytmkt.model.external.mediaitem.YtmSong
import dev.toastbits.ytmkt.model.internal.TextRun
import dev.toastbits.ytmkt.radio.BuiltInRadioContinuation
import dev.toastbits.ytmkt.radio.RadioContinuation
import io.ktor.client.call.body
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.put

class EchoPlaylistEndpoint(override val api: YoutubeiApi) : ApiEndpoint() {

    private val continuationEndpoint = EchoPlaylistContinuationEndpoint(api)

    private fun formatBrowseId(browseId: String) =
        if (!browseId.startsWith("VL") && !browseId.startsWith("MPREb_")) "VL$browseId"
        else browseId

    private fun cleanId(playlistId: String) =
        if (playlistId.startsWith("VL")) playlistId.substring(2)
        else playlistId

    suspend fun loadFromPlaylist(
        playlistId: String,
        params: String? = null,
        quality: ThumbnailProvider.Quality
    ): Triple<YtmPlaylist, String?, PagedData<Track>> = run {

        val endpoint = if (!playlistId.startsWith("MPREb_"))
            api.client.request {
                endpointPath("navigation/resolve_url")
                addApiHeadersWithAuthenticated()
                postWithBody {
                    put("url", "https://music.youtube.com/playlist?list=${cleanId(playlistId)}")
                }
            }.body<PlaylistEndpointResponse>().endpoint?.browseEndpoint?.takeIf {
                val pageType =
                    it.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType
                pageType == "MUSIC_PAGE_TYPE_PLAYLIST" || pageType == "MUSIC_PAGE_TYPE_ALBUM"
            }
        else null

        val id = endpoint?.browseId ?: formatBrowseId(playlistId)
        val param = endpoint?.params ?: params

        val res = api.client.request {
            endpointPath("browse")
            addApiHeadersWithAuthenticated()
            postWithBody(YoutubeiPostBody.BASE.getPostBody(api)) {
                put("browseId", id)
                if (param != null) {
                    put("params", param)
                }
            }
        }
        val (playlist, relation) =
            parsePlaylistResponse(cleanId(id), res, api.dataLocale.language, api)

        // ytm-kt 0.6.x regression: album-track rows can produce YtmArtists with a real
        // channel id but a NULL name (menu fallback runs before the text fallback).
        // Fill nameless artist names from the playlist/album header artists.
        val headerArtists = playlist.artists.orEmpty().filter { !it.name.isNullOrBlank() }
        fun fixArtistNames(song: YtmSong): YtmSong {
            val artists = song.artists ?: return song
            if (artists.all { !it.name.isNullOrBlank() }) return song
            return song.copy(
                artists = artists.map { artist ->
                    if (!artist.name.isNullOrBlank()) artist
                    else artist.copy(
                        name = headerArtists.firstOrNull { it.id == artist.id }?.name
                            ?: headerArtists.singleOrNull()?.name
                    )
                }
            )
        }

        val songs = PagedData.Continuous { token ->
            if (token == null) {
                val ytmSongs = (playlist.items ?: emptyList()).map { fixArtistNames(it) }
                val sets = playlist.item_set_ids!!
                Page(
                    ytmSongs.mapIndexed { index, it -> it.toTrack(quality, sets[index]) },
                    playlist.continuation?.token
                )
            } else {
                val (songs, setIds, cont) = continuationEndpoint.load(token)
                val ytmSongs = songs ?: emptyList()
                val sets = setIds ?: emptyList()
                Page(ytmSongs.mapIndexed { index, it -> it.toTrack(quality, sets[index]) }, cont)
            }
        }
        Triple(playlist, relation, songs)
    }


    @Serializable
    data class PlaylistEndpointResponse(
        val endpoint: Endpoint? = null
    ) {

        @Serializable
        data class Endpoint(
            val browseEndpoint: BrowseEndpoint? = null
        )

        @Serializable
        data class BrowseEndpoint(
            val browseId: String? = null,
            val params: String? = null,
            val browseEndpointContextSupportedConfigs: BrowseEndpointContextSupportedConfigs? = null
        )

        @Serializable
        data class BrowseEndpointContextSupportedConfigs(
            val browseEndpointContextMusicConfig: BrowseEndpointContextMusicConfig? = null
        )

        @Serializable
        data class BrowseEndpointContextMusicConfig(
            val pageType: String? = null
        )
    }

    companion object {
        private val regex = Regex("(\\d+) $SONGS")
        fun List<TextRun>.findSongCount(): Int? {
            val count = this.firstOrNull { it.text.contains(SONGS) }?.text ?: return null
            val result = regex.find(count)?.groupValues?.get(1)
            return result?.toIntOrNull()
        }

        private val trackRegex = Regex("(\\d+) track")
        fun List<TextRun>.findTrackCount(): Int? {
            val count = this.firstOrNull { it.text.contains("track") }?.text ?: return null
            val result = trackRegex.find(count)?.groupValues?.get(1)
            return result?.toIntOrNull()
        }

        suspend fun parsePlaylistResponse(
            playlistId: String,
            response: HttpResponse,
            hl: String,
            api: YtmApi
        ) = run {
            val parsed: YoutubeiBrowseResponse = response.body()
            val builder = YtmPlaylistBuilder(playlistId)
            val playlistData = parsed.contents?.twoColumnBrowseResultsRenderer?.tabs?.firstOrNull()
                ?.tabRenderer?.content?.sectionListRenderer?.contents?.firstOrNull()
                ?.getPlaylistData(hl)

            if (playlistData != null) {
                builder.name = playlistData.title
                builder.description = playlistData.description
                builder.thumbnail_provider = playlistData.thumbnail
                builder.artists = playlistData.artists
                builder.year = playlistData.year
                builder.owner_id = "${playlistData.explicit},${playlistData.isEditable}"
                builder.item_count = playlistData.count
                builder.total_duration = playlistData.duration
            }

            val sectionListRenderer = parsed.contents?.run {
                singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer
                    ?: twoColumnBrowseResultsRenderer?.secondaryContents?.sectionListRenderer
            }
            var continuationToken : String?=null
            val items = sectionListRenderer?.contents?.mapNotNull { row ->
                continuationToken = row.musicPlaylistShelfRenderer?.contents?.lastOrNull()
                    ?.continuationItemRenderer?.continuationEndpoint?.continuationCommand?.token
                row.getMediaItemsAndSetIds(hl, api)?.mapNotNull { (item, set) ->
                    if (item is YtmSong) item to set
                    else null
                } ?: return@mapNotNull null
            }?.flatten() ?: emptyList()

            builder.items = items.map { it.first }
            builder.item_set_ids = items.map { it.second ?: "Unknown" }
            builder.item_count = builder.item_count
            builder.continuation = continuationToken?.let {
                BuiltInRadioContinuation(it, BuiltInRadioContinuation.Type.PLAYLIST)
            }

            val continuationItems =
                parsed.continuationContents?.musicPlaylistShelfContinuation?.contents?.mapNotNull {
                    it.toMediaItemData(hl, api)
                }
            if (continuationItems != null) {
                builder.items = continuationItems.map { it.first }.filterIsInstance<YtmSong>()
                builder.item_set_ids = continuationItems.mapNotNull { it.second }
                val cont =
                    parsed.continuationContents.musicPlaylistShelfContinuation.continuations?.firstOrNull()?.nextContinuationData?.continuation
                builder.continuation =
                    cont?.let { BuiltInRadioContinuation(it, BuiltInRadioContinuation.Type.PLAYLIST) }
            }

            var relatedId =
                sectionListRenderer?.continuations?.firstOrNull()?.nextContinuationData?.continuation

            relatedId = relatedId ?: items.lastOrNull()?.first?.id?.let { "id://$it" }

            builder.build() to relatedId
        }
    }
}