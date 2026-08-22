package dev.brahmkshatriya.echo.extension.endpoints

import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.model.ApiEndpoint
import io.ktor.client.call.body
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class EchoVideoEndpoint(override val api: YoutubeiApi) : ApiEndpoint() {

    private suspend fun request(
        context: JsonObject,
        id: String,
        playlist: String? = null
    ): HttpResponse {
        return api.client.request {
            endpointPath("player")
            addApiHeadersWithoutAuthentication()
            postWithBody(context) {
                put("videoId", id)
                put("playlistId", playlist)
            }
        }
    }

    suspend fun getVideo(resolve: Boolean, id: String, playlist: String? = null) = coroutineScope {
        val web = async {
            if (resolve) request(webRemix, id, playlist)
                .body<YoutubeFormatResponse>().videoDetails.musicVideoType
            else null
        }
        val ios = request(iosContext(), id, playlist).body<YoutubeFormatResponse>()
        ios to web.await()
    }

    private fun iosContext() = buildJsonObject {
        put("context", buildJsonObject {
            put("client", buildJsonObject {
                put("clientName", "IOS")
                put("clientVersion", "19.34.2")
                put("visitorData", api.visitorId)
            })
        })
    }

    private val webRemix = buildJsonObject {
        put("context", buildJsonObject {
            put("client", buildJsonObject {
                put("clientName", "WEB_REMIX")
                put("clientVersion", "1.20220606.03.00")
            })
        })
    }
}

@Serializable
data class YoutubeFormatResponse(
    val playabilityStatus: PlayabilityStatus? = null,
    val streamingData: StreamingData? = null,
    val videoDetails: VideoDetails
)

@Serializable
data class PlayabilityStatus(
    val status: String,
    val reason: String? = null,
    val playableInEmbed: Boolean? = null
)

@Serializable
data class StreamingData(
    val expiresInSeconds: String? = null,
    val hlsManifestUrl: String? = null,
    val adaptiveFormats: List<AdaptiveFormat> = emptyList()
)

@Serializable
data class AdaptiveFormat(
    val itag: Long? = null,
    val url: String? = null,
    val signatureCipher: String? = null,
    val cipher: String? = null,
    val mimeType: String,
    val bitrate: Int,
    val width: Long? = null,
    val height: Long? = null,
    val initRange: Range? = null,
    val indexRange: Range? = null,
    val lastModified: String? = null,
    val contentLength: String? = null,
    val quality: String? = null,
    val fps: Long? = null,
    val qualityLabel: String? = null,
    val projectionType: String? = null,
    val averageBitrate: Long? = null,
    val approxDurationMs: String? = null,
    val colorInfo: ColorInfo? = null,
    val highReplication: Boolean? = null,
    val audioQuality: String? = null,
    val audioSampleRate: String? = null,
    val audioChannels: Long? = null,
    val loudnessDb: Double? = null
)

@Serializable
data class ColorInfo(
    val primaries: String? = null,
    val transferCharacteristics: String? = null,
    val matrixCoefficients: String? = null
)

@Serializable
data class Range(
    val start: String? = null,
    val end: String? = null
)

@Serializable
data class VideoDetails(
    val videoId: String, 
    val title: String? = null,
    val lengthSeconds: String? = null,
    val channelId: String? = null,
    val isOwnerViewing: Boolean? = null,
    val isCrawlable: Boolean? = null,
    val allowRatings: Boolean? = null,
    val viewCount: String? = null,
    val author: String? = null,
    val isPrivate: Boolean? = null,
    val isUnpluggedCorpus: Boolean? = null,
    val musicVideoType: String? = null,
    val isLiveContent: Boolean? = null,
    val shortDescription: String? = null,
)