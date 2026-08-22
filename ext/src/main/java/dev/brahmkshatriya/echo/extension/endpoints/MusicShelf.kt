package dev.brahmkshatriya.echo.extension.endpoints

import dev.toastbits.ytmkt.itemcache.MediaItemCache
import dev.toastbits.ytmkt.model.YtmApi
import dev.toastbits.ytmkt.model.external.Thumbnail
import dev.toastbits.ytmkt.model.external.ThumbnailProvider
import dev.toastbits.ytmkt.model.external.mediaitem.YtmArtist
import dev.toastbits.ytmkt.model.external.mediaitem.YtmMediaItem
import dev.toastbits.ytmkt.model.external.mediaitem.YtmPlaylist
import dev.toastbits.ytmkt.model.external.mediaitem.YtmSong
import dev.toastbits.ytmkt.model.internal.Header
import dev.toastbits.ytmkt.model.internal.HeaderRenderer
import dev.toastbits.ytmkt.model.internal.MusicResponsiveListItemRenderer
import dev.toastbits.ytmkt.model.internal.NavigationEndpoint
import dev.toastbits.ytmkt.model.internal.TextRuns
import dev.toastbits.ytmkt.model.internal.ThumbnailRenderer
import dev.toastbits.ytmkt.model.internal.YoutubeiHeader
import dev.toastbits.ytmkt.model.internal.YoutubeiHeaderContainer
import dev.toastbits.ytmkt.radio.YoutubeiNextResponse
import dev.toastbits.ytmkt.uistrings.parseYoutubeDurationString
import sh.syk.kmpresources.library.model.Locale
import kotlinx.serialization.Serializable

@Serializable
data class MusicTwoRowItemRenderer(
    val navigationEndpoint: NavigationEndpoint,
    val title: TextRuns,
    val subtitle: TextRuns?,
    val thumbnailRenderer: ThumbnailRenderer,
    val menu: Menu?,
    val subtitleBadges: List<MusicResponsiveListItemRenderer.Badge>?
) {
    fun toYtmMediaItem(api: YtmApi): YtmMediaItem? {
        fun getArtists(
            hostItem: YtmMediaItem,
            api: YtmApi
        ): List<YtmArtist>? {
            val artists: List<YtmArtist>? = subtitle?.runs?.mapNotNull { run ->
                val browseEndpoint: dev.toastbits.ytmkt.model.internal.BrowseEndpoint? =
                    run.navigationEndpoint?.browseEndpoint
                if (browseEndpoint?.browseId == null || browseEndpoint.getMediaItemType() != YtmMediaItem.Type.ARTIST) {
                    return@mapNotNull null
                }

                return@mapNotNull YtmArtist(
                    browseEndpoint.browseId!!,
                    name = run.text
                )
            }

            if (!artists.isNullOrEmpty()) {
                return artists
            }

            if (hostItem is YtmSong) {
                val songType: YtmSong.Type? = api.itemCache.getSong(
                    hostItem.id,
                    setOf(MediaItemCache.SongKey.TYPE)
                )?.type

                val index: Int = if (songType == YtmSong.Type.VIDEO) 0 else 1
                subtitle?.runs?.getOrNull(index)?.also {
                    return listOf(
                        YtmArtist(YtmArtist.getForItemId(hostItem)).copy(
                            name = it.text
                        )
                    )
                }
            }

            return null
        }

        // Video
        val watchEndpoint = navigationEndpoint.watchEndpoint
        val playlistEndpoint = navigationEndpoint.watchPlaylistEndpoint
        return if (watchEndpoint?.videoId != null) {
            val album: YtmPlaylist? = menu?.menuRenderer?.items?.find {
                it.menuNavigationItemRenderer?.navigationEndpoint?.browseEndpoint?.getMediaItemType() == YtmMediaItem.Type.PLAYLIST
            }?.menuNavigationItemRenderer?.navigationEndpoint?.browseEndpoint?.browseId?.let {
                YtmPlaylist(YtmPlaylist.cleanId(it))
            }
            val thumbnail =
                thumbnailRenderer.musicThumbnailRenderer?.thumbnail?.thumbnails?.firstOrNull()
            val songId: String = YtmSong.cleanId(watchEndpoint.videoId!!)
            YtmSong(
                id = songId,
                type =
                if (thumbnail?.height == thumbnail?.width) YtmSong.Type.SONG
                else YtmSong.Type.VIDEO,
                name = this.title.first_text,
                thumbnail_provider = thumbnailRenderer.toThumbnailProvider(),
                artists = getArtists(YtmSong(songId), api),
                is_explicit = menu?.menuRenderer?.title?.musicMenuTitleRenderer?.endButtons?.firstOrNull()?.likeButtonRenderer?.likeStatus == "LIKE",
                album = album
            )
        } else if (playlistEndpoint != null) {
            YtmPlaylist(
                id = YtmPlaylist.cleanId(playlistEndpoint.playlistId),
                type = YtmPlaylist.Type.RADIO,
                name = title.first_text,
                thumbnail_provider = thumbnailRenderer.toThumbnailProvider()
            )
        } else {
            val endpoint = navigationEndpoint.browseEndpoint
            // Playlist or artist
            val browseId: String = endpoint?.browseId ?: return null
            val pageType: String = endpoint.getPageType() ?: return null

            val title: String = title.first_text
            val thumbnailProvider = thumbnailRenderer.toThumbnailProvider()

            when (YtmMediaItem.Type.fromBrowseEndpointType(pageType)) {
                YtmMediaItem.Type.SONG -> {
                    val songId: String = YtmSong.cleanId(browseId)
                    YtmSong(
                        songId,
                        name = title,
                        thumbnail_provider = thumbnailProvider,
                        artists = getArtists(YtmSong(songId), api)
                    )
                }

                YtmMediaItem.Type.ARTIST -> YtmArtist(
                    browseId,
                    name = title,
                    thumbnail_provider = thumbnailProvider
                )

                YtmMediaItem.Type.PLAYLIST -> {
                    val playlistId: String = YtmPlaylist.cleanId(browseId)
                    YtmPlaylist(
                        playlistId,
                        type = YtmPlaylist.Type.fromBrowseEndpointType(pageType),
                        artists = getArtists(YtmPlaylist(playlistId), api),
                        name = title,
                        thumbnail_provider = thumbnailProvider,
                        year = subtitle?.runs?.find { it.text.length == 4 }?.text?.toIntOrNull()
                    )
                }

                null -> null
            }
        }
    }
}

val timeRegex = Regex("""\d{1,2}:\d{1,2}""")
fun MusicTwoColumnItemRenderer.Run?.isTime(): Boolean {
    val text = this?.text ?: return false
    return timeRegex.matches(text)
}

@Serializable
data class MusicTwoColumnItemRenderer(
    val thumbnail: MusicMenuTitleRendererThumbnail? = null,
    val thumbnailAspectRatio: String? = null,
    val title: Subtitle? = null,
    val subtitle: Subtitle? = null,
    val navigationEndpoint: MusicTwoColumnItemRendererNavigationEndpoint? = null,
    val menu: Menu? = null,
    val playlistItemData: PlaylistItemData? = null,
    val menuIconDisplayPolicy: String? = null
) {

    fun toMediaItemData(hl: String): Pair<YtmMediaItem, String?>? {
        return YtmSong(
            id = navigationEndpoint?.watchEndpoint?.videoId ?: return null,
            name = title?.runs?.firstOrNull()?.text,
            artists = menu?.menuRenderer?.items?.mapNotNull { item ->
                item.menuNavigationItemRenderer?.navigationEndpoint?.browseEndpoint?.takeIf {
                    it.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType == "MUSIC_PAGE_TYPE_ARTIST"
                }?.let {
                    val browseId = it.browseId ?: return@let null
                    val name = subtitle?.runs?.take(3)
                        ?.filter { run ->
                            val text = run.text ?: return@filter false
                            !run.isTime() && !text.contains("plays")
                        }
                        ?.joinToString("") { run -> run.text ?: "" }
                        ?.substringBeforeLast("•")
                        ?: return@let null

                    YtmArtist(browseId, name)
                }
            },
            thumbnail_provider = thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails?.let {
                ThumbnailProvider.fromThumbnails(it)
            },
            duration = subtitle?.runs?.find { it.isTime() }?.text?.let {
                parseYoutubeDurationString(it, Locale.parse(hl))?.inWholeMilliseconds
            },
            type = YtmSong.Type.SONG,
            is_explicit = menu?.menuRenderer?.title?.musicMenuTitleRenderer?.endButtons?.firstOrNull()?.likeButtonRenderer?.likeStatus == "LIKE"
        ) to playlistItemData?.playlistSetVideoId
    }


    @Serializable
    data class Menu(
        val menuRenderer: MenuRenderer? = null
    )

    @Serializable
    data class MenuRenderer(
        val items: List<Item>? = null,
        val title: Title? = null,
        val accessibility: Accessibility? = null
    )

    @Serializable
    data class Accessibility(
        val accessibilityData: AccessibilityData? = null
    )

    @Serializable
    data class AccessibilityData(
        val label: String? = null
    )

    @Serializable
    data class Item(
        val menuNavigationItemRenderer: MenuNavigationItemRenderer? = null
    )

    @Serializable
    data class MenuNavigationItemRenderer(
        val text: Subtitle? = null,
        val icon: Icon? = null,
        val navigationEndpoint: MenuNavigationItemRendererNavigationEndpoint? = null,
        val trackingParams: String? = null
    )

    @Serializable
    data class Icon(
        val iconType: String? = null
    )

    @Serializable
    data class MenuNavigationItemRendererNavigationEndpoint(
        val clickTrackingParams: String? = null,
        val browseEndpoint: BrowseEndpoint? = null
    )

    @Serializable
    data class BrowseEndpoint(
        val browseId: String? = null,
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

    @Serializable
    data class Subtitle(
        val runs: List<Run>? = null
    )

    @Serializable
    data class Run(
        val text: String? = null
    )

    @Serializable
    data class Title(
        val musicMenuTitleRenderer: MusicMenuTitleRenderer? = null
    )

    @Serializable
    data class MusicMenuTitleRenderer(
        val primaryText: Subtitle? = null,
        val secondaryText: SecondaryText? = null,
        val thumbnail: MusicMenuTitleRendererThumbnail? = null,
        val endButtons: List<EndButton>? = null
    )

    @Serializable
    data class EndButton(
        val likeButtonRenderer: LikeButtonRenderer? = null
    )

    @Serializable
    data class LikeButtonRenderer(
        val target: Target? = null,
        val likeStatus: String? = null,
        val trackingParams: String? = null,
        val likesAllowed: Boolean? = null,
        val serviceEndpoints: List<ServiceEndpoint>? = null
    )

    @Serializable
    data class ServiceEndpoint(
        val clickTrackingParams: String? = null,
        val likeEndpoint: LikeEndpoint? = null
    )

    @Serializable
    data class LikeEndpoint(
        val status: String? = null,
        val target: Target? = null
    )

    @Serializable
    data class Target(
        val videoId: String? = null
    )

    @Serializable
    data class SecondaryText(
        val runs: List<Run>? = null,
        val accessibility: Accessibility? = null
    )

    @Serializable
    data class MusicMenuTitleRendererThumbnail(
        val musicThumbnailRenderer: MusicThumbnailRenderer? = null
    )

    @Serializable
    data class MusicThumbnailRenderer(
        val thumbnail: MusicThumbnailRendererThumbnail? = null,
        val thumbnailCrop: String? = null,
        val thumbnailScale: String? = null,
        val trackingParams: String? = null
    )

    @Serializable
    data class MusicThumbnailRendererThumbnail(
        val thumbnails: List<Thumbnail>? = null
    )


    @Serializable
    data class MusicTwoColumnItemRendererNavigationEndpoint(
        val watchEndpoint: WatchEndpoint? = null
    )

    @Serializable
    data class WatchEndpoint(
        val videoId: String? = null,
        val playlistId: String? = null,
        val params: String? = null,
        val playlistSetVideoId: String? = null
    )

    @Serializable
    data class PlaylistItemData(
        val playlistSetVideoId: String? = null
    )

}

@Serializable
data class MusicPlaylistShelfRenderer(
    val playlistId: String? = null,
    val contents: List<YoutubeiBrowseResponse.YoutubeiShelf.YoutubeiShelfContentsItem>? = null,
    val continuations: List<Continuation>? = null,
    val subFooter: SubFooter? = null,
) {

    @Serializable
    data class SubFooter(
        val messageRenderer: MessageRenderer
    )

    @Serializable
    data class MessageRenderer(
        val subtext: Subtext
    )

    @Serializable
    data class Subtext(
        val messageSubtextRenderer: MessageSubtextRenderer
    )

    @Serializable
    data class MessageSubtextRenderer(
        val text: TextRuns
    )

    @Serializable
    data class Continuation(
        val nextContinuationData: NextContinuationData? = null
    )

    @Serializable
    data class NextContinuationData(
        val continuation: String? = null,
        val clickTrackingParams: String? = null,
        val autoloadEnabled: Boolean? = null,
        val autoloadImmediately: Boolean? = null
    )
}

@Serializable
data class ItemSectionRenderer(val contents: List<ItemSectionRendererContent>)

@Serializable
data class ItemSectionRendererContent(val didYouMeanRenderer: DidYouMeanRenderer?)

@Serializable
data class DidYouMeanRenderer(val correctedQuery: TextRuns)


@Serializable
data class GridRenderer(
    val items: List<YoutubeiBrowseResponse.YoutubeiShelf.YoutubeiShelfContentsItem>? = null,
    override val header: GridHeader? = null,
) : YoutubeiHeaderContainer

@Serializable
data class GridHeader(val gridHeaderRenderer: HeaderRenderer?) : YoutubeiHeader {
    override val header_renderer: HeaderRenderer?
        get() = gridHeaderRenderer
}

@Serializable
data class MusicCarouselShelfRenderer(
    override val header: Header? = null,  
    val contents: List<YoutubeiBrowseResponse.YoutubeiShelf.YoutubeiShelfContentsItem>? = null,
) : YoutubeiHeaderContainer

@Serializable
data class MusicDescriptionShelfRenderer(val description: TextRuns, val header: TextRuns?)

@Serializable
data class MusicCardShelfRenderer(
    val thumbnail: ThumbnailRenderer,
    val title: TextRuns,
    val subtitle: TextRuns,
    val menu: Menu,
    override val header: Header? = null  
) : YoutubeiHeaderContainer

@Serializable
data class Menu(val menuRenderer: MenuRenderer?)

@Serializable
data class MenuRenderer(
    val items: List<YoutubeiNextResponse.MenuItem>? = null,
    val title: Title? = null
)

@Serializable
data class Title(
    val musicMenuTitleRenderer: MusicMenuTitleRenderer? = null
)

@Serializable
data class MusicMenuTitleRenderer(
    val primaryText: PrimaryText? = null,
    val secondaryText: SecondaryText? = null,
    val thumbnail: MusicMenuTitleRendererThumbnail? = null,
    val endButtons: List<EndButton>? = null
)

@Serializable
data class EndButton(
    val likeButtonRenderer: LikeButtonRenderer? = null
)

@Serializable
data class LikeButtonRenderer(
    val target: Target? = null,
    val likeStatus: String? = null,
    val likesAllowed: Boolean? = null,
    val serviceEndpoints: List<ServiceEndpoint>? = null
)

@Serializable
data class ServiceEndpoint(
    val likeEndpoint: LikeEndpoint? = null
)

@Serializable
data class LikeEndpoint(
    val status: String? = null,
    val target: Target? = null
)

@Serializable
data class Target(
    val videoId: String? = null
)

@Serializable
data class PrimaryText(
    val runs: List<Run>? = null
)

@Serializable
data class Run(
    val text: String? = null
)

@Serializable
data class SecondaryText(
    val runs: List<Run>? = null,
)

@Serializable
data class MusicMenuTitleRendererThumbnail(
    val musicThumbnailRenderer: MusicThumbnailRenderer? = null
)

@Serializable
data class MusicThumbnailRenderer(
    val thumbnail: MusicThumbnailRendererThumbnail? = null,
    val thumbnailCrop: String? = null,
    val thumbnailScale: String? = null,
    val trackingParams: String? = null
)

@Serializable
data class MusicThumbnailRendererThumbnail(
    val thumbnails: List<Thumbnail>? = null
)
