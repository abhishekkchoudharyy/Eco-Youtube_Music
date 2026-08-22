package dev.brahmkshatriya.echo.extension.endpoints

import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiPostBody
import dev.toastbits.ytmkt.model.ApiEndpoint
import dev.toastbits.ytmkt.model.external.mediaitem.YtmSong
import io.ktor.client.call.body
import io.ktor.client.request.request

class EchoPlaylistContinuationEndpoint(override val api: YoutubeiApi) : ApiEndpoint() {
    suspend fun load(
        ctoken: String?
    ): Triple<List<YtmSong>?, List<String>?, String?> = run {
        val response = api.client.request {
            endpointPath("browse")
            addApiHeadersWithAuthenticated()
            if (ctoken != null) {
                url.parameters.append("ctoken", ctoken)
                url.parameters.append("continuation", ctoken)
                url.parameters.append("type", "next")
            }
            postWithBody(YoutubeiPostBody.BASE.getPostBody(api))
        }
        val parsed = response.body<YtContinuation>().onResponseReceivedActions.first()
            .appendContinuationItemsAction.continuationItems

        val cont = parsed.lastOrNull()
            ?.continuationItemRenderer?.continuationEndpoint?.continuationCommand?.token
        val items = parsed.mapNotNull { it.toMediaItemData(api.dataLocale.language, api) }

        Triple(
            items.map { it.first }.filterIsInstance<YtmSong>(),
            items.mapNotNull { it.second },
            cont
        )
    }
}