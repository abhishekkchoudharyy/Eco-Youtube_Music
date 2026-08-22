package dev.brahmkshatriya.echo.extension.endpoints

import dev.toastbits.ytmkt.endpoint.SongFeedFilterChip
import dev.toastbits.ytmkt.endpoint.SongFeedLoadResult
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.model.ApiEndpoint
import dev.toastbits.ytmkt.model.external.MediaItemYoutubePage
import dev.toastbits.ytmkt.model.external.PlainYoutubePage
import dev.toastbits.ytmkt.model.external.mediaitem.MediaItemLayout
import dev.toastbits.ytmkt.model.external.mediaitem.YtmMediaItem
import dev.toastbits.ytmkt.model.internal.YoutubeiHeaderContainer
import dev.toastbits.ytmkt.uistrings.RawUiString
import dev.toastbits.ytmkt.uistrings.YoutubeUiString
import io.ktor.client.call.body
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

val PLAIN_HEADERS: List<String> =
    listOf("accept-language", "user-agent", "accept-encoding", "content-encoding", "origin")

open class EchoSongFeedEndpoint(override val api: YoutubeiApi) : ApiEndpoint() {

    suspend fun getSongFeed(
        minRows: Int = -1,
        params: String? = null,
        continuation: String? = null,
        browseId: String? = null
    ) = runCatching {
        val hl: String = api.dataLocale.language

        suspend fun performRequest(ctoken: String?): YoutubeiBrowseResponse {
            val response: HttpResponse = api.client.request {
                endpointPath("browse")

                if (ctoken != null) {
                    url.parameters.append("ctoken", ctoken)
                    url.parameters.append("continuation", ctoken)
                    url.parameters.append("type", "next")
                }

                // NOTE: apply the whitelisted plain headers FIRST — in ytm-kt 0.6.x
                // addApiHeadersWithoutAuthentication(include) strips any header not in
                // `include`, which would remove cookie/authorization added earlier.
                addApiHeadersWithoutAuthentication(PLAIN_HEADERS)
                addApiHeadersWithAuthenticated()
                postWithBody {
                    if (params != null) {
                        put("params", params)
                    }
                    if (browseId != null) {
                        put("browseId", browseId)
                    }
                }
            }
            return response.body()
        }

        var data: YoutubeiBrowseResponse = performRequest(continuation)
        val headerChips: List<SongFeedFilterChip>? = data.getHeaderChips(hl)

        val rows: MutableList<MediaItemLayout> = processRows(
            data.getShelves(continuation != null), api
        ).toMutableList()

        var ctoken: String? = data.ctoken
        while (ctoken != null && minRows >= 1 && rows.size < minRows) {
            data = performRequest(ctoken)
            ctoken = data.ctoken

            val shelves = data.getShelves(true)
            if (shelves.isEmpty()) {
                break
            }

            rows.addAll(processRows(shelves, api))
        }

        return@runCatching SongFeedLoadResult(rows, ctoken, headerChips)
    }

    companion object {
        val clientContext = buildJsonObject {
            put("context", buildJsonObject {
                put("client", buildJsonObject {
                    put("clientName", "26")
                    put("clientVersion", "6.48.2")
                })
            })
        }

        fun processRows(
            rows: List<YoutubeiBrowseResponse.YoutubeiShelf>, api: YoutubeiApi
        ): List<MediaItemLayout> {
            val hl = api.dataLocale.language
            fun String.createUiString() =
                YoutubeUiString.Type.HOME_FEED.createFromKey(this, api.dataLocale)

            return rows.mapNotNull { row ->
                val items = row.getMediaItems(hl, api) ?: return@mapNotNull null
                val default = MediaItemLayout(items, "".createUiString(), null, null, null)
                when (val renderer = row.getRenderer()) {
                    is YoutubeiBrowseResponse.YoutubeiShelf.MusicShelfRenderer -> {
                        val mediaItem = renderer.bottomEndpoint?.getMediaItem()
                        MediaItemLayout(
                            items,
                            (renderer.title?.first_text ?: "").createUiString(),
                            null,
                            null,
                            mediaItem?.let { renderer.bottomEndpoint.getViewMore(it) }
                        )
                    }

                    is YoutubeiHeaderContainer -> {
                        val header = renderer.header?.header_renderer ?: return@mapNotNull default
                        val titleTextRun = header.title ?: return@mapNotNull default
                        val browseEndpoint =
                            titleTextRun.runs?.first()?.navigationEndpoint?.browseEndpoint
                        val browseId = browseEndpoint?.browseId
                        val pageType = browseEndpoint?.browseEndpointContextSupportedConfigs
                            ?.browseEndpointContextMusicConfig?.pageType
                        val title = titleTextRun.first_text.createUiString()

                        val subtitle = (header.subtitle ?: header.strapline)?.first_text?.let {
                            RawUiString(
                                it.lowercase().replaceFirstChar { char -> char.uppercase() })
                        }
                        val page = when {
                            browseId?.startsWith("FEmusic_") == true ->
                                PlainYoutubePage(browseId)

                            pageType != null && browseId != null -> {
                                val mediaItem =
                                    YtmMediaItem.Type.fromBrowseEndpointType(pageType)
                                        ?.itemFromId(browseId)
                                mediaItem?.let { MediaItemYoutubePage(it, browseEndpoint.params) }
                            }

                            else -> null
                        }
                        MediaItemLayout(items, title, subtitle, null, page)
                    }

                    else -> {
                        println("Unknown shelf type: $renderer")
                        default
                    }
                }
            }
        }
    }
}
