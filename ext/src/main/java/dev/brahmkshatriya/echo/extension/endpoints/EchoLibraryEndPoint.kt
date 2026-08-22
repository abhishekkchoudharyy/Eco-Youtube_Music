package dev.brahmkshatriya.echo.extension.endpoints

import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.model.ApiEndpoint
import dev.toastbits.ytmkt.model.external.mediaitem.YtmMediaItem
import dev.toastbits.ytmkt.model.external.mediaitem.YtmPlaylist
import io.ktor.client.call.body
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.json.put

class EchoLibraryEndPoint(override val api: YoutubeiApi) : ApiEndpoint() {
    suspend fun loadLibraryFeed(
        id: String, ctoken: String? = null
    ): Pair<List<YtmMediaItem>, String?> = run {
        val hl: String = api.dataLocale.language
        val response: HttpResponse = api.client.request {
            endpointPath("browse")
            if (ctoken != null) {
                url.parameters.append("ctoken", ctoken)
                url.parameters.append("continuation", ctoken)
                url.parameters.append("type", "next")
            }
            addApiHeadersWithAuthenticated()
            postWithBody {
                put("browseId", id)
            }
        }
        val data: YoutubeiBrowseResponse = response.body()

        val contents =
            data.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents
        val list = contents?.map { content ->
            val musicShelf =
                content.musicShelfRenderer ?: data.continuationContents?.musicShelfContinuation
            val grid = content.gridRenderer?.items
            val items = musicShelf?.contents ?: grid ?: emptyList()

            items.mapNotNull { contentsItem ->
                val item: YtmMediaItem? = contentsItem.toMediaItemData(hl, api)?.first
                if (item is YtmPlaylist) {
                    if (contentsItem.musicTwoRowItemRenderer?.navigationEndpoint?.browseEndpoint == null) {
                        return@mapNotNull null
                    }
                    contentsItem.musicTwoRowItemRenderer.menu?.menuRenderer?.items
                        ?.findLast { it.menuNavigationItemRenderer?.icon?.iconType == "DELETE" }
                        ?.let { return@mapNotNull item.copy(owner_id = api.userAuthState?.own_channel_id) }
                }
                item
            }
        }?.flatten() ?: emptyList()
        val continuation =
            contents?.lastOrNull()?.musicShelfRenderer?.continuations?.firstOrNull()?.nextContinuationData?.continuation
        return list to continuation
    }
}