package dev.brahmkshatriya.echo.extension.endpoints

import sh.syk.kmpresources.library.model.Locale
import dev.toastbits.ytmkt.endpoint.SearchFilter
import dev.toastbits.ytmkt.endpoint.SearchResults
import dev.toastbits.ytmkt.endpoint.SearchType
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiPostBody
import dev.toastbits.ytmkt.impl.youtubei.endpoint.YTMGetSongFeedEndpoint
import dev.toastbits.ytmkt.model.ApiEndpoint
import dev.toastbits.ytmkt.model.external.ItemLayoutType
import dev.toastbits.ytmkt.model.external.mediaitem.MediaItemLayout
import dev.toastbits.ytmkt.model.external.mediaitem.YtmArtist
import dev.toastbits.ytmkt.model.external.mediaitem.YtmPlaylist
import dev.toastbits.ytmkt.model.external.mediaitem.YtmSong
import dev.toastbits.ytmkt.model.internal.DidYouMeanRenderer
import dev.toastbits.ytmkt.model.internal.ItemSectionRenderer
import dev.toastbits.ytmkt.model.internal.MusicCardShelfRenderer
import dev.toastbits.ytmkt.model.internal.NavigationEndpoint
import dev.toastbits.ytmkt.model.internal.TextRuns
import dev.toastbits.ytmkt.model.internal.YoutubeiShelf
import dev.toastbits.ytmkt.uistrings.YoutubeUiString
import io.ktor.client.call.body
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.put

class EchoSearchEndpoint(override val api: YoutubeiApi) : ApiEndpoint() {
    suspend fun search(
        query: String,
        params: String?,
        auth: Boolean = true,
        nonMusic: Boolean = false
    ): Result<SearchResults> = runCatching {
        val hl: String = api.dataLocale.language
        val response: HttpResponse = api.client.request {
            endpointPath("search", non_music_api = nonMusic)
            if (auth) addApiHeadersWithAuthenticated(non_music_api = nonMusic)
            else addApiHeadersWithoutAuthentication(non_music_api = nonMusic)
            postWithBody(
                (if (nonMusic) YoutubeiPostBody.WEB else YoutubeiPostBody.DEFAULT).getPostBody(api)
            ) {
                put("query", query)
                put("params", params)
            }
        }

        val parsed: YoutubeiSearchResponse = response.body()

        val sectionListRenderers: List<SectionListRenderer> =
            parsed.contents.getSectionListRenderers() ?: emptyList()

        var correctionSuggestion: String? = null

        val categories: List<YoutubeiShelf> =
            sectionListRenderers.flatMap { renderer ->
                renderer.contents.orEmpty().filter { shelf ->
                    val didYouMeanRenderer: DidYouMeanRenderer? =
                        shelf.itemSectionRenderer?.contents?.firstOrNull()?.didYouMeanRenderer

                    if (didYouMeanRenderer != null) {
                        correctionSuggestion = didYouMeanRenderer.correctedQuery.first_text
                        return@filter false
                    } else {
                        return@filter true
                    }
                }
            }

        val categoryLayouts: MutableList<Pair<MediaItemLayout, SearchFilter?>> = mutableListOf()
        val chips =
            sectionListRenderers.flatMap { it.header?.chipCloudRenderer?.chips ?: emptyList() }

        for ((index, category) in categories.withIndex()) {
            val card: MusicCardShelfRenderer? = category.musicCardShelfRenderer
            val key: String? =
                card?.header?.musicCardShelfHeaderBasicRenderer?.title?.firstTextOrNull()
            if (card != null && key != null) {
                try {
                    categoryLayouts.add(
                        Pair(
                            MediaItemLayout(
                                mutableListOf(card.getMediaItem()),
                                YoutubeUiString.Type.SEARCH_PAGE.createFromKey(key, Locale.parse(hl)),
                                null,
                                type = ItemLayoutType.CARD
                            ),
                            null
                        )
                    )
                    continue
                } catch (e: Exception) {
                    println("DEBUG: Failed to process MusicCardShelfRenderer: ${e.message}")
                }
            }

            val itemSectionRenderer: ItemSectionRenderer? = category.itemSectionRenderer
            if (itemSectionRenderer != null) {
                categoryLayouts.add(
                    Pair(MediaItemLayout(itemSectionRenderer.getMediaItems(), null, null), null)
                )
                continue
            }

            val shelf: YTMGetSongFeedEndpoint.MusicShelfRenderer =
                category.musicShelfRenderer ?: continue
            val items =
                shelf.contents?.mapNotNull { it.toMediaItemAndPlaylistSetVideoId(Locale.parse(hl), api)?.first }?.toMutableList()
                    ?: continue
            val searchParams =
                if (index == 0) null else chips.getOrNull(index - 1)?.chipCloudChipRenderer?.navigationEndpoint?.searchEndpoint?.params

            val title: String? = shelf.title?.firstTextOrNull()
            if (title != null) {
                categoryLayouts.add(Pair(
                    MediaItemLayout(
                        items,
                        YoutubeUiString.Type.SEARCH_PAGE.createFromKey(title, Locale.parse(hl)),
                        null
                    ),
                    searchParams?.let {
                        val item = items.firstOrNull() ?: return@let null
                        SearchFilter(
                            when (item) {
                                is YtmSong ->
                                    if (item.type == YtmSong.Type.VIDEO) SearchType.VIDEO else SearchType.SONG

                                is YtmArtist ->
                                    SearchType.ARTIST

                                is YtmPlaylist ->
                                    when (item.type) {
                                        YtmPlaylist.Type.ALBUM -> SearchType.ALBUM
                                        else -> SearchType.PLAYLIST
                                    }

                                else -> throw NotImplementedError(item::class.toString())
                            },
                            it
                        )
                    }
                ))
            }
        }

        if (correctionSuggestion == null && query.trim().lowercase() == "recursion") {
            correctionSuggestion = query
        }

        return@runCatching SearchResults(categoryLayouts, correctionSuggestion)
    }
}

@Serializable
private data class YoutubeiSearchResponse(
    val contents: Contents
) {
    @Serializable
    data class Contents(
        val tabbedSearchResultsRenderer: TabbedSearchResultsRenderer?,
        val twoColumnSearchResultsRenderer: TwoColumnSearchResultsRenderer?
    ) {
        fun getSectionListRenderers(): List<SectionListRenderer>? =
            tabbedSearchResultsRenderer?.tabs?.mapNotNull { it.tabRenderer.content?.sectionListRenderer }
                ?: twoColumnSearchResultsRenderer?.primaryContents?.let { listOf(it.sectionListRenderer) }
    }

    @Serializable
    data class TabbedSearchResultsRenderer(val tabs: List<Tab>) {
        @Serializable
        data class Tab(val tabRenderer: TabRenderer)

        @Serializable
        data class TabRenderer(val content: Content?)
    }

    @Serializable
    data class TwoColumnSearchResultsRenderer(val primaryContents: Content)

    @Serializable
    data class Content(val sectionListRenderer: SectionListRenderer)
}

@Serializable
data class SectionListRenderer(
    val contents: List<YoutubeiShelf>?,
    val header: ChipCloudRendererHeader?
)

@Serializable
data class ChipCloudRendererHeader(val chipCloudRenderer: ChipCloudRenderer?)

@Serializable
data class ChipCloudRenderer(val chips: List<Chip>)

@Serializable
data class Chip(val chipCloudChipRenderer: ChipCloudChipRenderer)

@Serializable
data class ChipCloudChipRenderer(val navigationEndpoint: NavigationEndpoint, val text: TextRuns?)
