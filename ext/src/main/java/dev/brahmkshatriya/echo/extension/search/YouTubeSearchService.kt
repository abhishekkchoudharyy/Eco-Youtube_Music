package dev.brahmkshatriya.echo.extension.search

import sh.syk.kmpresources.library.model.Locale
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.toAlbum
import dev.brahmkshatriya.echo.extension.toArtist
import dev.brahmkshatriya.echo.extension.toPlaylist
import dev.brahmkshatriya.echo.extension.toTrack
import dev.toastbits.ytmkt.endpoint.SearchResults
import dev.toastbits.ytmkt.endpoint.SearchType
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.model.external.ThumbnailProvider
import dev.toastbits.ytmkt.model.external.mediaitem.YtmArtist
import dev.toastbits.ytmkt.model.external.mediaitem.YtmPlaylist
import dev.toastbits.ytmkt.model.external.mediaitem.YtmSong
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope


class YouTubeSearchService(
    private val api: YoutubeiApi
) {
    data class CategorySearchResult(
        val type: SearchType,
        val shelves: List<Shelf>,
        val error: Exception? = null
    )
    suspend fun searchAllCategories(
        query: String,
        thumbnailQuality: ThumbnailProvider.Quality
    ): List<CategorySearchResult> = coroutineScope {
        val searchTypes = listOf(
            SearchType.SONG,
            SearchType.VIDEO,
            SearchType.ALBUM,
            SearchType.ARTIST,
            SearchType.PLAYLIST
        )
        
        searchTypes.map { searchType ->
            async {
                searchCategory(query, searchType, thumbnailQuality)
            }
        }.map { it.await() }
    }

    suspend fun searchCategory(
        query: String,
        searchType: SearchType,
        thumbnailQuality: ThumbnailProvider.Quality
    ): CategorySearchResult {
        return try {
            val searchResult = api.Search.search(
                query,
                params = searchType.getDefaultParams()
            ).getOrThrow()
            
            val shelves = convertSearchResultsToShelves(searchResult, thumbnailQuality)
            CategorySearchResult(searchType, shelves)
        } catch (e: Exception) {
            println("${searchType.name} search failed: ${e.message}")
            CategorySearchResult(searchType, emptyList(), e)
        }
    }
    
    private suspend fun convertSearchResultsToShelves(
        searchResults: SearchResults,
        thumbnailQuality: ThumbnailProvider.Quality
    ): List<Shelf> {
        val shelves = mutableListOf<Shelf>()
        
        for ((layout, _) in searchResults.categories) {
            val title = layout.title?.get(Locale.parse("en")) ?: "Results"
            val items = layout.items
            
            if (items.isEmpty()) continue
            
            val echoItems = items.mapNotNull { item ->
                convertMediaItem(item, thumbnailQuality)
            }
            
            if (echoItems.isEmpty()) continue
            
            val shelf = createShelfFromItems(title, echoItems)
            shelves.add(shelf)
        }
        
        return shelves
    }

    private fun convertMediaItem(
        item: Any,
        thumbnailQuality: ThumbnailProvider.Quality
    ): EchoMediaItem? {
        return when (item) {
            is YtmSong -> item.toTrack(thumbnailQuality)
            is YtmArtist -> item.toArtist(thumbnailQuality)
            is YtmPlaylist -> {
                if (item.type == YtmPlaylist.Type.ALBUM) {
                    item.toAlbum(false, thumbnailQuality)
                } else {
                    item.toPlaylist(thumbnailQuality)
                }
            }
            else -> null
        }
    }
    
    private fun createShelfFromItems(title: String, items: List<EchoMediaItem>): Shelf {
        val shelfId = "search_${title.lowercase().replace(" ", "_")}_${System.currentTimeMillis()}"
        
        return if (items.all { it is Track }) {
            Shelf.Lists.Tracks(
                id = shelfId,
                title = title,
                list = items.filterIsInstance<Track>()
            )
        } else {
            Shelf.Lists.Items(
                id = shelfId,
                title = title,
                list = items
            )
        }
    }

    fun mergeCategoryResults(results: List<CategorySearchResult>): List<Shelf> {
        return results
            .filter { it.error == null && it.shelves.isNotEmpty() }
            .flatMap { it.shelves }
    }
}
