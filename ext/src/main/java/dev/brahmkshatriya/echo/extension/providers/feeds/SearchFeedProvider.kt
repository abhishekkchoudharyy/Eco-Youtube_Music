package dev.brahmkshatriya.echo.extension.providers.feeds

import sh.syk.kmpresources.library.model.Locale
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.extension.YoutubeExtension
import dev.brahmkshatriya.echo.extension.endpoints.EchoSongFeedEndpoint
import dev.brahmkshatriya.echo.extension.search.YouTubeSearchService
import dev.brahmkshatriya.echo.extension.toShelf
import dev.toastbits.ytmkt.endpoint.SearchType
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.model.external.ThumbnailProvider


class SearchFeedProvider(
    private val api: YoutubeiApi,
    private val searchService: YouTubeSearchService,
    private val songFeedEndpoint: EchoSongFeedEndpoint
) {
    suspend fun loadSearchFeed(
        query: String,
        thumbnailQuality: ThumbnailProvider.Quality
    ): Feed<Shelf> {
        if (query.isBlank()) {
            return loadBrowseFeed(thumbnailQuality)
        }
        val tabs = listOf(
            Tab("all", "All"),
            Tab("songs", "Songs"),
            Tab("videos", "Videos"),
            Tab("albums", "Albums"),
            Tab("artists", "Artists"),
            Tab("playlists", "Playlists")
        )

        return Feed(tabs) { tab ->
            when (tab?.id) {
                "all" -> searchAllCategories(query, thumbnailQuality)
                "songs" -> searchCategory(query, SearchType.SONG, thumbnailQuality)
                "videos" -> searchCategory(query, SearchType.VIDEO, thumbnailQuality)
                "albums" -> searchCategory(query, SearchType.ALBUM, thumbnailQuality)
                "artists" -> searchCategory(query, SearchType.ARTIST, thumbnailQuality)
                "playlists" -> searchCategory(query, SearchType.PLAYLIST, thumbnailQuality)
                else -> searchAllCategories(query, thumbnailQuality)
            }
        }
    }

    private suspend fun loadBrowseFeed(
        thumbnailQuality: ThumbnailProvider.Quality
    ): Feed<Shelf> {
        val result = songFeedEndpoint.getSongFeed().getOrThrow()
        
        val filterChips = result.filter_chips?.map {
            Tab(
                id = it.params,
                title = it.text.get(Locale.parse(YoutubeExtension.ENGLISH)),
                isSort = false,
                extras = mapOf(
                    "browseId" to it.params,
                    "category" to it.text.get(Locale.parse(YoutubeExtension.ENGLISH)),
                    "isFilterChip" to "true",
                    "isHomeFeedTab" to "true"
                )
            )
        } ?: emptyList()

        return Feed(filterChips) { tab ->
            val pagedData = PagedData.Continuous { continuation ->
                try {
                    val params = tab?.id
                    val browseResult = songFeedEndpoint.getSongFeed(
                        params = params,
                        continuation = continuation
                    ).getOrThrow()

                    val data = browseResult.layouts.map { itemLayout ->
                        itemLayout.toShelf(api, YoutubeExtension.SINGLES, thumbnailQuality)
                    }

                    Page(data, browseResult.ctoken)
                } catch (e: Exception) {
                    Page(emptyList(), null)
                }
            }
            Feed.Data(pagedData)
        }
    }

    private suspend fun searchAllCategories(
        query: String,
        thumbnailQuality: ThumbnailProvider.Quality
    ): Feed.Data<Shelf> {
        return try {
            val results = searchService.searchAllCategories(query, thumbnailQuality)
            val shelves = searchService.mergeCategoryResults(results)
            shelves.toFeedData()
        } catch (e: Exception) {
            println("All categories search failed: ${e.message}")
            emptyList<Shelf>().toFeedData()
        }
    }

    private suspend fun searchCategory(
        query: String,
        searchType: SearchType,
        thumbnailQuality: ThumbnailProvider.Quality
    ): Feed.Data<Shelf> {
        return try {
            val result = searchService.searchCategory(query, searchType, thumbnailQuality)
            result.shelves.toFeedData()
        } catch (e: Exception) {
            println("${searchType.name} search failed: ${e.message}")
            emptyList<Shelf>().toFeedData()
        }
    }
}
