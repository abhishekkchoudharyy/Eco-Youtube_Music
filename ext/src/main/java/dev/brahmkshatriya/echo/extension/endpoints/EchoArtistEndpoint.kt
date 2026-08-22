package dev.brahmkshatriya.echo.extension.endpoints

import sh.syk.kmpresources.library.model.Locale
import dev.brahmkshatriya.echo.extension.endpoints.EchoSongFeedEndpoint.Companion.processRows
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiPostBody
import dev.toastbits.ytmkt.model.ApiEndpoint
import dev.toastbits.ytmkt.model.external.ItemLayoutType
import dev.toastbits.ytmkt.model.external.ThumbnailProvider
import dev.toastbits.ytmkt.model.external.mediaitem.YtmArtist
import dev.toastbits.ytmkt.model.external.mediaitem.YtmArtistBuilder
import dev.toastbits.ytmkt.model.external.mediaitem.YtmArtistLayout
import dev.toastbits.ytmkt.model.internal.HeaderRenderer
import dev.toastbits.ytmkt.uistrings.parseYoutubeSubscribersString
import io.ktor.client.call.body
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.json.put

class EchoArtistEndpoint(override val api: YoutubeiApi) : ApiEndpoint() {

    suspend fun loadArtist(id: String): YtmArtist {
        val hl: String = api.dataLocale.language
        val response: HttpResponse = api.client.request {
            endpointPath("browse")
            addApiHeadersWithAuthenticated()
            postWithBody(YoutubeiPostBody.MOBILE.getPostBody(api)) {
                put("browseId", id)
            }
        }

        return parseArtistResponse(id, response, hl, api).getOrThrow()
    }

    private suspend fun parseArtistResponse(
        artistId: String,
        response: HttpResponse,
        hl: String,
        api: YoutubeiApi
    ): Result<YtmArtist> = runCatching {
        val parsed: YoutubeiBrowseResponse = response.body()
        val builder = YtmArtistBuilder(artistId)

        val headerRenderer: HeaderRenderer? = parsed.header?.getRenderer()
        if (headerRenderer != null) {
            builder.name = headerRenderer.title!!.first_text
            builder.description = headerRenderer.description?.first_text
            builder.thumbnail_provider =
                ThumbnailProvider.fromThumbnails(headerRenderer.getThumbnails())

            headerRenderer.subscriptionButton?.subscribeButtonRenderer?.let { subscribeButton ->
                builder.subscribe_channel_id = subscribeButton.channelId
                builder.subscriber_count = parseYoutubeSubscribersString(
                    subscribeButton.subscriberCountText.first_text,
                    Locale.parse(hl)
                )
                builder.subscribed = subscribeButton.subscribed
            }
            headerRenderer.playButton?.buttonRenderer?.let {
                if (it.icon?.iconType == "MUSIC_SHUFFLE") {
                    builder.shuffle_playlist_id = it.navigationEndpoint?.watchEndpoint?.playlistId
                }
            }
        }

        val shelfList = parsed.getShelves(false)
        builder.layouts = processRows(shelfList, api).map {
            YtmArtistLayout(
                items = it.items,
                title = it.title,
                type = ItemLayoutType.GRID,
                view_more = it.view_more,
                playlist_id = null
            )
        }

        return@runCatching builder.build()
    }

}