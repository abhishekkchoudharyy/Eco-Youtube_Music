package dev.brahmkshatriya.echo.extension.streaming

import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.RealNewPipeVideoFormatsEndpoint
import dev.brahmkshatriya.echo.extension.utils.RetryUtils
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi


class YouTubeStreamResolver(
    private val api: YoutubeiApi,
    private val settings: Settings,
    private val videoEndpoint: dev.brahmkshatriya.echo.extension.endpoints.EchoVideoEndpoint,
    private val visitorEndpoint: dev.brahmkshatriya.echo.extension.endpoints.EchoVisitorEndpoint
) {
    private fun getRealNewPipe() = RealNewPipeVideoFormatsEndpoint()
    
    private val ytmKtEndpoint by lazy {
        dev.brahmkshatriya.echo.extension.YtmKtVideoFormatsEndpoint(api)
    }
    
    private val maxVideoQuality: Int
        get() = settings.getString("video_quality")?.toIntOrNull() ?: 480  

    suspend fun resolveStreamable(videoId: String, preferVideos: Boolean): Streamable.Media {
        println("Loading streamable media for video ID: $videoId")
        
        return RetryUtils.retryWithBackoff(
            maxRetries = 3,
            initialDelay = 1000L,
            maxDelay = 10000L
        ) {
            resolveStreamableInternal(videoId, preferVideos)
        }
    }
    
   //Three-tier fallback strategy

    private suspend fun resolveStreamableInternal(videoId: String, preferVideos: Boolean): Streamable.Media {
        val errorReasons = mutableListOf<String>()
        
        //RealNewPipe
        println("RealNewPipe extractor for video ID: $videoId, preferVideos: $preferVideos")
        val realNewPipeResult = tryRealNewPipeExtractor(videoId, preferVideos)
        if (realNewPipeResult is ExtractionResult.Success) {
            println("Successfully loaded streamable media using RealNewPipe extractor")
            return realNewPipeResult.media
        } else if (realNewPipeResult is ExtractionResult.Error) {
            errorReasons.add("RealNewPipe: ${realNewPipeResult.reason}")
        }
        
        //MultipleVideoFormatsEndpoint
        println("RealNewPipe failed, trying YtmKt MultipleVideoFormatsEndpoint for video ID: $videoId")
        val ytmKtResult = tryYtmKtEndpoint(videoId, preferVideos)
        if (ytmKtResult is ExtractionResult.Success) {
            println("Successfully loaded streamable media using YtmKt library")
            return ytmKtResult.media
        } else if (ytmKtResult is ExtractionResult.Error) {
            errorReasons.add("YtmKt: ${ytmKtResult.reason}")
        }
        
        //YouTube Music API 
        println("YtmKt failed, trying YouTube Music API for video ID: $videoId")
        val youtubeMusicResult = tryYouTubeMusicApi(videoId, preferVideos)
        if (youtubeMusicResult is ExtractionResult.Success) {
            println("Successfully loaded streamable media using YouTube Music API")
            return youtubeMusicResult.media
        } else if (youtubeMusicResult is ExtractionResult.Error) {
            errorReasons.add("YouTube Music API: ${youtubeMusicResult.reason}")
        }
        
        // All fallbacks failed
        val detailedError = if (errorReasons.isNotEmpty()) {
            "Failed to resolve streaming URLs for video $videoId:\n" + errorReasons.joinToString("\n")
        } else {
            "Failed to resolve streaming URLs for video $videoId - all extraction methods failed"
        }
        throw Exception(detailedError)
    }
    
    private sealed class ExtractionResult {
        data class Success(val media: Streamable.Media) : ExtractionResult()
        data class Error(val reason: String) : ExtractionResult()
        object Failed : ExtractionResult()
    }
    

    private suspend fun tryRealNewPipeExtractor(videoId: String, preferVideos: Boolean): ExtractionResult {
        return try {
            if (preferVideos) {
                println("RealNewPipe Attempting to get muxed video+audio streams for: $videoId (max quality: ${maxVideoQuality}p)")
                val muxedResult = getRealNewPipe().getMuxedVideoStreams(videoId)

                if (muxedResult.isSuccess) {
                    val allMuxedFormats = muxedResult.getOrThrow()
                    println("RealNewPipe Got ${allMuxedFormats.size} total muxed video+audio streams")

                    val muxedFormats = if (maxVideoQuality < Int.MAX_VALUE) {
                        allMuxedFormats.filter { format ->
                            val heightMatch = Regex("(\\d+)p").find(format.mimeType)
                            val height = heightMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            height <= maxVideoQuality
                        }.also {
                            println("RealNewPipe Filtered to ${it.size} streams <= ${maxVideoQuality}p")
                        }
                    } else {
                        println("RealNewPipe Using all available qualities (Best Available)")
                        allMuxedFormats
                    }

                    if (muxedFormats.isEmpty()) {
                        println("RealNewPipe No muxed streams match quality setting ${maxVideoQuality}p, trying audio-only fallback")
                    } else {
                        val muxedSources = muxedFormats.map { format ->
                            val qualityKbps = if (format.bitrate > 0) format.bitrate / 1000 else 0
                            Streamable.Source.Http(
                                request = NetworkRequest(url = format.url!!),
                                type = Streamable.SourceType.Progressive,
                                quality = qualityKbps,
                                title = format.mimeType
                            )
                        }

                        println("RealNewPipe Returning ${muxedSources.size} muxed video+audio sources (instant playback, perfect sync)")
                        return ExtractionResult.Success(Streamable.Media.Server(muxedSources, merged = false))
                    }
                } else {
                    println("RealNewPipe Muxed streams failed: ${muxedResult.exceptionOrNull()?.message}")
                }
            }

            println("RealNewPipe Attempting to get audio streams for: $videoId")
            val streamsResult = getRealNewPipe().getSeparateStreams(
                videoId = videoId,
                maxQuality = null
            )
            
            if (streamsResult.isFailure) {
                println("RealNewPipe Failed to get audio streams: ${streamsResult.exceptionOrNull()?.message}")
                return ExtractionResult.Failed
            }
            
            val (_, audioFormats) = streamsResult.getOrThrow()
            println("RealNewPipe Got ${audioFormats.size} audio streams")
            
            if (audioFormats.isEmpty()) {
                println("RealNewPipe No audio streams available")
                return ExtractionResult.Failed
            }
            
            val audioSources = audioFormats.map { format ->
                val bitrateKbps = if (format.bitrate > 0) format.bitrate / 1000 else 0
                Streamable.Source.Http(
                    request = NetworkRequest(url = format.url!!),
                    type = Streamable.SourceType.Progressive,
                    quality = bitrateKbps,
                    title = "Audio - ${format.mimeType} - ${bitrateKbps}kbps"
                )
            }
            
            println("RealNewPipe Returning ${audioSources.size} audio-only sources (instant playback)")
            return ExtractionResult.Success(Streamable.Media.Server(audioSources, merged = false))
        } catch (e: Exception) {
            println("RealNewPipe Exception: ${e.message}")
            e.printStackTrace()
            val errorMsg = e.message ?: "Unknown error"
            return when {
                errorMsg.contains("age", ignoreCase = true) || errorMsg.contains("restricted", ignoreCase = true) -> 
                    ExtractionResult.Error("Age-restricted content (Not playable at the moment)")
                errorMsg.contains("geo", ignoreCase = true) || errorMsg.contains("region", ignoreCase = true) || errorMsg.contains("blocked", ignoreCase = true) -> 
                    ExtractionResult.Error("Geo-blocked (Seems not available in your region)")
                errorMsg.contains("unavailable", ignoreCase = true) -> 
                    ExtractionResult.Error("Video unavailable")
                else -> ExtractionResult.Error(errorMsg)
            }
        }
    }
    
    private suspend fun tryYtmKtEndpoint(videoId: String, preferVideos: Boolean): ExtractionResult {
        return try {
            println("YtmKt Attempting to get formats for: $videoId, preferVideos: $preferVideos")
            
            if (preferVideos) {
                val muxedResult = ytmKtEndpoint.getMuxedVideoStreams(videoId)
                
                if (muxedResult.isSuccess) {
                    val allMuxedFormats = muxedResult.getOrThrow()
                    println("YtmKt: Got ${allMuxedFormats.size} total muxed video+audio streams")
                    
                    val muxedFormats = if (maxVideoQuality < Int.MAX_VALUE) {
                        allMuxedFormats.filter { format ->
                            val heightMatch = Regex("(\\d+)p").find(format.mimeType)
                            val height = heightMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            height <= maxVideoQuality
                        }.also {
                            println("YtmKt Filtered to ${it.size} streams <= ${maxVideoQuality}p")
                        }
                    } else {
                        allMuxedFormats
                    }
                    
                    if (muxedFormats.isNotEmpty()) {
                        val muxedSources = muxedFormats.map { format ->
                            val qualityKbps = if (format.bitrate > 0) format.bitrate / 1000 else 0
                            Streamable.Source.Http(
                                request = NetworkRequest(url = format.url!!),
                                type = Streamable.SourceType.Progressive,
                                quality = qualityKbps,
                                title = format.mimeType
                            )
                        }
                        
                        println("YtmKt: Returning ${muxedSources.size} muxed video+audio sources")
                        return ExtractionResult.Success(Streamable.Media.Server(muxedSources, merged = false))
                    }
                }
                
                println("YtmKt Muxed streams failed or empty, falling back to audio-only")
            }
            
            val audioResult = ytmKtEndpoint.getAudioFormats(videoId)
            
            if (audioResult.isFailure) {
                val error = audioResult.exceptionOrNull()
                println("YtmKt: Failed to get audio formats: ${error?.message}")
                return ExtractionResult.Error(error?.message ?: "Failed to get audio formats")
            }
            
            val audioFormats = audioResult.getOrThrow()
            println("YtmKt Got ${audioFormats.size} audio streams")
            
            if (audioFormats.isEmpty()) {
                println("YtmKt No audio streams available")
                return ExtractionResult.Error("No audio streams available")
            }
            
            val audioSources = audioFormats.map { format ->
                val bitrateKbps = if (format.bitrate > 0) format.bitrate / 1000 else 0
                Streamable.Source.Http(
                    request = NetworkRequest(url = format.url!!),
                    type = Streamable.SourceType.Progressive,
                    quality = bitrateKbps,
                    title = "Audio - ${format.mimeType} - ${bitrateKbps}kbps"
                )
            }
            
            println("YtmKt Returning ${audioSources.size} audio-only sources")
            return ExtractionResult.Success(Streamable.Media.Server(audioSources, merged = false))
        } catch (e: Exception) {
            println("YtmKt: Exception: ${e.message}")
            e.printStackTrace()
            val errorMsg = e.message ?: "Unknown error"
            return ExtractionResult.Error(errorMsg)
        }
    }
    
    private suspend fun tryYouTubeMusicApi(videoId: String, preferVideos: Boolean): ExtractionResult {
        return try {
            println("Falling back to YouTube Music API for video ID: $videoId")
            
            try {
                if (api.visitorId == null) {
                    println("Visitor ID is null, trying to get a new one...")
                    api.visitorId = visitorEndpoint.getVisitorId()
                    println("Successfully set visitor ID: ${api.visitorId}")
                } else {
                    println("Using existing visitor ID: ${api.visitorId}")
                }
            } catch (e: Exception) {
                println("Exception ensuring visitor ID: ${e.message}")
            }
            
            println("YouTube Music API: Fetching formats for: $videoId")
            val (video, _) = videoEndpoint.getVideo(true, videoId)
            
            if (video.streamingData == null) {
                val errorMsg = "No streaming data available from YouTube Music API. The video may be restricted, age-gated, or unavailable."
                println("YouTube Music API: $errorMsg")
                return ExtractionResult.Error(errorMsg)
            }
            
            val streamingData = video.streamingData
            val adaptiveFormats = streamingData.adaptiveFormats
            if (adaptiveFormats.isEmpty()) {
                println("YouTube Music API No adaptive formats available")
                return ExtractionResult.Error("No audio sources found in YouTube Music API response")
            }
            
            println("YouTube Music API Got ${adaptiveFormats.size} adaptive formats")
            
            val audioFormats = adaptiveFormats
                .filter { it.mimeType.lowercase().contains("audio/") && it.url != null }
            
            println("YouTube Music API: ${audioFormats.size} audio formats found")
            
            if (audioFormats.isEmpty()) {
                println("YouTube Music API: No audio formats available")
                return ExtractionResult.Error("No audio sources found in YouTube Music API response")
            }
            
            val audioSources = audioFormats.map { format ->
                val bitrateKbps = if (format.bitrate > 0) format.bitrate / 1000 else 128
                Streamable.Source.Http(
                    request = NetworkRequest(url = format.url!!),
                    type = Streamable.SourceType.Progressive,
                    quality = bitrateKbps,
                    title = "Audio - ${format.mimeType}${if (format.bitrate > 0) " - ${bitrateKbps}kbps" else ""}"
                )
            }
            
            println("Successfully loaded streamable media using YouTube Music API with ${audioSources.size} quality options")
            return ExtractionResult.Success(Streamable.Media.Server(audioSources, merged = false))
        } catch (e: Exception) {
            println("YouTube Music API also failed: ${e.message}")
            e.printStackTrace()
            val errorMsg = e.message ?: "Unknown error"
            return ExtractionResult.Error(errorMsg)
        }
    }
}
