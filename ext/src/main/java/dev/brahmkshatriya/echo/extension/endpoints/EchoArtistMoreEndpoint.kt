package dev.brahmkshatriya.echo.extension.endpoints

import dev.brahmkshatriya.echo.extension.endpoints.EchoSongFeedEndpoint.Companion.processRows
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiPostBody
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.model.ApiEndpoint
import dev.toastbits.ytmkt.model.external.YoutubePage
import io.ktor.client.call.body
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.json.put

class EchoArtistMoreEndpoint(override val api: YoutubeiApi) : ApiEndpoint() {

    suspend fun load(param: YoutubePage.BrowseParamsData) = run {
        val response: HttpResponse = api.client.request {
            endpointPath("browse")
            addApiHeadersWithAuthenticated()
            postWithBody(YoutubeiPostBody.MOBILE.getPostBody(api)) {
                put("browseId", param.browseId)
                put("params", param.browseParams)
            }
        }
        val data: YoutubeiBrowseResponse = response.body()
        val contents =
            data.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()
                ?.tabRenderer?.content?.sectionListRenderer?.contents
                ?: data.continuationContents?.sectionListContinuation?.contents
        contents?.let { processRows(it, api) } ?: emptyList()
    }
}