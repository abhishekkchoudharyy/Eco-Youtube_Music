package dev.brahmkshatriya.echo.extension.providers.feeds

import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.extension.YoutubeExtension
import dev.brahmkshatriya.echo.extension.endpoints.EchoSongFeedEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoVisitorEndpoint
import dev.brahmkshatriya.echo.extension.toShelf
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.model.external.ThumbnailProvider


class HomeFeedProvider(
    private val api: YoutubeiApi,
    private val songFeedEndpoint: EchoSongFeedEndpoint,
    private val visitorEndpoint: EchoVisitorEndpoint
) {
    suspend fun loadHomeFeed(thumbnailQuality: ThumbnailProvider.Quality): Feed<Shelf> {
        if (api.visitorId == null) {
            try {
                val visitorId = visitorEndpoint.getVisitorId()
                api.visitorId = visitorId
                println("HomeFeed Initialized visitor_id: $visitorId")
            } catch (e: Exception) {
                println("HomeFeed Failed to initialize visitor_id: ${e.message}")
            }
        } else {
            println("HomeFeed Using existing visitor_id: ${api.visitorId}")
        }
        
        val tabs = listOf<dev.brahmkshatriya.echo.common.models.Tab>()
        
        return Feed(tabs) { tab ->
            val pagedData = PagedData.Continuous { continuation ->
                try {
                    val result = songFeedEndpoint.getSongFeed(
                        params = tab?.id,
                        continuation = continuation
                    ).getOrThrow()
                    
                    val data = result.layouts.map { itemLayout ->
                        itemLayout.toShelf(api, YoutubeExtension.SINGLES, thumbnailQuality)
                    }
                    
                    Page(data, result.ctoken)
                } catch (e: Exception) {
                    println("HomeFeed Error loading page - ${e.message}")
                    e.printStackTrace()
                    Page(emptyList(), null)
                }
            }
            Feed.Data(pagedData)
        }
    }
}
