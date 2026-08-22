package dev.brahmkshatriya.echo.extension

import dev.toastbits.ytmkt.model.external.YoutubeVideoFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.TimeUnit


class RealNewPipeVideoFormatsEndpoint {

    companion object {
        @Volatile
        private var initialized = false
        
        private const val OPUS_DEFAULT_BITRATE = 160000  
        private const val AAC_DEFAULT_BITRATE = 128000  
        private const val FALLBACK_BITRATE = 128000      
        
        private val httpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
                .retryOnConnectionFailure(true)
                .build()
        }

        private fun ensureInitialized() {
            if (initialized) return
            synchronized(this) {
                if (initialized) return

                NewPipe.init(object : Downloader() {
                    override fun execute(request: Request): Response {
                        return runBlocking(Dispatchers.IO) {
                            val builder = okhttp3.Request.Builder().url(request.url())
                            request.headers().forEach { (name, values) ->
                                values.forEach { value -> builder.addHeader(name, value) }
                            }
                            request.dataToSend()?.let { data ->
                                builder.post(data.toRequestBody(null))
                            }

                            val response = httpClient.newCall(builder.build()).execute()
                            val body = response.body?.string()
                            Response(
                                response.code,
                                response.message,
                                response.headers.toMultimap(),
                                body,
                                response.request.url.toString()
                            )
                        }
                    }
                })

                initialized = true
                println("Initialized with OkHttp downloader")
            }
        }
    }

    suspend fun getSeparateStreams(
        videoId: String,
        maxQuality: Int? = null
    ): Result<Pair<List<YoutubeVideoFormat>, List<YoutubeVideoFormat>>> = runCatching {
        ensureInitialized()

        val url = "https://www.youtube.com/watch?v=$videoId"
        println("Extracting separate streams for $videoId (maxQuality=$maxQuality)")

        val streamInfo = try {
            withContext(Dispatchers.IO) {
                StreamInfo.getInfo(ServiceList.YouTube, url)
            }
        } catch (e: ContentNotAvailableException) {
            println("Video unavailable - ${e.message}")
            throw Exception("Video unavailable: ${e.message}", e)
        } catch (e: ParsingException) {
            println("Parsing failed (possible API change) - ${e.message}")
            throw Exception("Failed to parse video data: ${e.message}", e)
        } catch (e: ExtractionException) {
            println("Extraction failed - ${e.message}")
            throw Exception("Extraction failed: ${e.message}", e)
        } catch (e: Exception) {
            println("Unexpected error - ${e.javaClass.simpleName}: ${e.message}")
            throw Exception("Failed to get video info: ${e.message}", e)
        }

        kotlin.coroutines.coroutineContext.ensureActive()

        val videoOnlyStreams = streamInfo.videoOnlyStreams.filter { stream ->
            stream.isUrl &&  
            !stream.content.isNullOrEmpty() &&
            stream.itag > 0 &&
            (maxQuality == null || (stream.height ?: 0) <= maxQuality)
        }

        println("Got ${videoOnlyStreams.size} video-only streams")

        val videoFormats = videoOnlyStreams.mapNotNull { videoStream ->
                val itag = videoStream.itag
                val urlStr = videoStream.content?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val height = videoStream.height ?: 0
                val fps = videoStream.fps
                
            val mediaFormat = videoStream.format
            val mimeType = mediaFormat?.mimeType ?: "video/unknown"

            val format = YoutubeVideoFormat(
                itag = itag,
                mimeType = "Video-$mimeType-${height}p${fps}fps",
                bitrate = height * fps * 1000, 
                url = urlStr,  
                loudnessDb = null
            )
            height to format  
        }
        .sortedByDescending { it.first }  
        .map { it.second }  

        val allAudioStreams = streamInfo.audioStreams.distinctBy { it.itag }
        println("Got ${allAudioStreams.size} audio streams")

        val audioFormats = allAudioStreams
            .filter { it.isUrl && !it.content.isNullOrEmpty() && it.itag > 0 }
            .mapNotNull { audioStream ->
                val itag = audioStream.itag
                val urlStr = audioStream.content?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                
                val mediaFormat = audioStream.format
                val mimeType = mediaFormat?.mimeType ?: "audio/unknown"
                val formatName = mediaFormat?.name ?: "unknown"

                val avg = audioStream.averageBitrate
                val direct = audioStream.bitrate

                val bitrate = when {
                    avg != AudioStream.UNKNOWN_BITRATE -> avg * 1000
                    direct != AudioStream.UNKNOWN_BITRATE -> direct
                    else -> when {
                        mimeType.contains("opus", true) || formatName.contains("opus", true) -> OPUS_DEFAULT_BITRATE
                        mimeType.contains("aac", true) || mimeType.contains("mp4a", true) -> AAC_DEFAULT_BITRATE
                        formatName.contains("m4a", true) -> AAC_DEFAULT_BITRATE
                        else -> FALLBACK_BITRATE
                    }
                }

                YoutubeVideoFormat(
                    itag = itag,
                    mimeType = "Audio-$mimeType-$formatName",
                    bitrate = bitrate,
                    url = urlStr,
                    loudnessDb = null
                )
            }
            .sortedWith(
                compareByDescending<YoutubeVideoFormat> { it.mimeType.contains("opus", true) }
                    .thenByDescending { it.bitrate }
            )

        println("Returning ${videoFormats.size} video formats and ${audioFormats.size} audio formats")

        if (videoFormats.isEmpty() && audioFormats.isEmpty()) {
            throw Exception("No valid streams found for $videoId")
        }

        Pair(videoFormats, audioFormats)
    }

    suspend fun getMuxedVideoStreams(
        videoId: String
    ): Result<List<YoutubeVideoFormat>> = runCatching {
        ensureInitialized()

        val url = "https://www.youtube.com/watch?v=$videoId"
        println("Extracting muxed (video+audio) streams for $videoId")

        val streamInfo = try {
            withContext(Dispatchers.IO) {
                StreamInfo.getInfo(ServiceList.YouTube, url)
            }
        } catch (e: ContentNotAvailableException) {
            println("Video unavailable - ${e.message}")
            throw Exception("Video unavailable: ${e.message}", e)
        } catch (e: ParsingException) {
            println("Parsing failed (possible API change) - ${e.message}")
            throw Exception("Failed to parse video data: ${e.message}", e)
        } catch (e: ExtractionException) {
            println("Extraction failed - ${e.message}")
            throw Exception("Extraction failed: ${e.message}", e)
        } catch (e: Exception) {
            println("Unexpected error - ${e.javaClass.simpleName}: ${e.message}")
            throw Exception("Failed to get video info: ${e.message}", e)
        }

        kotlin.coroutines.coroutineContext.ensureActive()

        val muxedStreams = streamInfo.videoStreams.filter { stream ->
            stream.isUrl &&
            !stream.content.isNullOrEmpty() &&
            stream.itag > 0
        }

        println("Got ${muxedStreams.size} muxed video+audio streams")

        val muxedFormats = muxedStreams.mapNotNull { videoStream ->
            val itag = videoStream.itag
            val urlStr = videoStream.content?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val height = videoStream.height ?: 0
            val fps = videoStream.fps

            val mediaFormat = videoStream.format
            val mimeType = mediaFormat?.mimeType ?: "video/unknown"

            val format = YoutubeVideoFormat(
                itag = itag,
                mimeType = "Muxed-$mimeType-${height}p${fps}fps",
                bitrate = height * fps * 1000,
                url = urlStr,
                loudnessDb = null
            )
            height to format
        }
        .sortedByDescending { it.first }
        .map { it.second }

        println("Returning ${muxedFormats.size} muxed video+audio formats")

        if (muxedFormats.isEmpty()) {
            throw Exception("No muxed video+audio streams found for $videoId")
        }

        muxedFormats
    }

    suspend fun getAudioFormats(
        videoId: String
    ): Result<List<YoutubeVideoFormat>> = runCatching {
        getSeparateStreams(videoId, maxQuality = null).getOrThrow().second
    }
    
    fun close() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
        httpClient.cache?.close()
    }
}

