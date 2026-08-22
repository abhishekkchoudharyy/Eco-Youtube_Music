package dev.brahmkshatriya.echo.extension.endpoints

import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.toTrack
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.model.external.ThumbnailProvider

/**
 * Enhanced song endpoint that intelligently combines data from multiple sources.
 * Optimized to try ytm-kt first, then conditionally fetch legacy if needed.
 */
class EchoEnhancedSongEndpoint(
    private val api: YoutubeiApi,
    private val echoSongEndpoint: EchoSongEndPoint
) {
    /**
     * Load track data by combining ytm-kt LoadSong and custom EchoSongEndpoint.
     * Optimized to try ytm-kt first, then conditionally fetch legacy only if needed.
     * 
     * @param trackId YouTube video/song ID
     * @param fallbackTrack Original track for fallback data
     * @param thumbnailQuality Quality for thumbnail images
     * @return Enhanced Track with merged data from both sources
     */
    suspend fun loadEnhancedTrack(
        trackId: String, 
        fallbackTrack: Track,
        thumbnailQuality: ThumbnailProvider.Quality
    ): Track {
        println("EchoEnhancedSongEndpoint: Loading track $trackId, fallback isVideo=${fallbackTrack.extras["isVideo"]}")
        
        // Try ytm-kt first (faster, better quality data)
        val ytmTrack = runCatching {
            api.LoadSong.loadSong(trackId).getOrThrow()
        }.map { it.toTrack(thumbnailQuality) }.getOrNull()
        
        if (ytmTrack != null) {
            // Check if we need legacy data for missing extras (lyricsId, relatedId, isLiked)
            val needsLegacyExtras = ytmTrack.extras["lyricsId"] == null || 
                                     ytmTrack.extras["relatedId"] == null ||
                                     ytmTrack.extras["isLiked"] == null
            
            if (needsLegacyExtras) {
                println("ytm-kt track missing extras, fetching from legacy endpoint")
                val legacyTrack = runCatching {
                    echoSongEndpoint.loadSong(trackId).getOrThrow()
                }.getOrNull()
                
                val mergedExtras = buildMergedExtras(ytmTrack, legacyTrack, trackId, fallbackTrack)
                return mergeWithYtmPriority(ytmTrack, legacyTrack, fallbackTrack, mergedExtras)
            } else {
                println("ytm-kt track has all required extras, skipping legacy fetch")
                val mergedExtras = buildMergedExtras(ytmTrack, null, trackId, fallbackTrack)
                return mergeWithYtmPriority(ytmTrack, null, fallbackTrack, mergedExtras)
            }
        }
        
        // Fallback to legacy if ytm-kt failed
        println("ytm-kt failed, trying legacy endpoint")
        val legacyTrack = runCatching {
            echoSongEndpoint.loadSong(trackId).getOrThrow()
        }.getOrNull()
        
        val mergedExtras = buildMergedExtras(null, legacyTrack, trackId, fallbackTrack)
        
        return when {
            legacyTrack != null -> mergeWithLegacyPriority(legacyTrack, fallbackTrack, mergedExtras)
            else -> createFallbackTrack(fallbackTrack, mergedExtras, trackId)
        }
    }
    
    /**
     * Build merged extras map from all available sources.
     */
    private fun buildMergedExtras(ytmTrack: Track?, legacyTrack: Track?, trackId: String, fallbackTrack: Track? = null): MutableMap<String, String> {
        return mutableMapOf<String, String>().apply {
            // Add ytm extras first (lowest priority)
            ytmTrack?.extras?.let { 
                println("  ytm extras: $it")
                putAll(it) 
            }
            
            // Add legacy extras (medium priority - contains lyricsId, relatedId, isLiked)
            legacyTrack?.extras?.let { 
                println("  legacy extras: $it")
                putAll(it) 
            }
            
            // Add fallback extras last (HIGHEST priority - preserves isVideo!)
            fallbackTrack?.extras?.let { 
                println("  fallback extras: $it")
                putAll(it) 
            }
            
            // Ensure videoId is always present
            if (!containsKey("videoId")) {
                put("videoId", trackId)
            }
            
            println("  final merged isVideo=${get("isVideo")}")
        }
    }
    
    /**
     * Pick the first artist list where EVERY entry has a real (non-blank, non-"Unknown") name.
     * ytm-kt 0.6.x returns artists with a channel id but NULL name; Convertors renders
     * those as the literal string "Unknown", so treat that as missing.
     */
    companion object {
        fun isValidArtistName(artist: dev.brahmkshatriya.echo.common.models.Artist): Boolean =
            !artist.name.isNullOrBlank() && !artist.name.equals("unknown", ignoreCase = true)

        fun pickBestArtists(vararg candidates: List<dev.brahmkshatriya.echo.common.models.Artist>?): List<dev.brahmkshatriya.echo.common.models.Artist> {
            for (candidate in candidates) {
                if (!candidate.isNullOrEmpty() && candidate.all { isValidArtistName(it) }) {
                    return candidate
                }
            }
            return candidates.filterNotNull().firstOrNull { it.isNotEmpty() } ?: emptyList()
        }
    }

    /**
     * Merge strategy when ytm-kt track is available (preferred source).
     * Falls back to legacy/original track for missing fields.
     */
    private fun mergeWithYtmPriority(
        ytmTrack: Track,
        legacyTrack: Track?,
        fallbackTrack: Track,
        mergedExtras: Map<String, String>
    ): Track {
        // ytmTrack from ytm-kt NEVER has streamables, so we must create them
        val streamables = if (ytmTrack.streamables.isNotEmpty()) {
            ytmTrack.streamables
        } else {
            createDefaultStreamable(mergedExtras["videoId"]!!)
        }
        
        return ytmTrack.copy(
            // Prefer ytm cover, fallback to original then legacy
            cover = ytmTrack.cover ?: fallbackTrack.cover ?: legacyTrack?.cover,
            
            // Prefer ytm album, fallback to legacy
            album = ytmTrack.album ?: legacyTrack?.album,
            
            // Prefer ytm artists if they have real names, fallback to legacy then original.
            // (ytm-kt 0.6.x returns artists with a channel id but NULL name; Convertors
            // renders those as the literal string "Unknown", so treat that as missing too)
            artists = pickBestArtists(ytmTrack.artists, legacyTrack?.artists, fallbackTrack.artists),
            
            // Add streamables - THIS WAS MISSING!
            streamables = streamables,
            
            // Use merged extras with all available metadata
            extras = mergedExtras
        )
    }
    
    /**
     * Merge strategy when only legacy track is available.
     * Ensures streamables are always present.
     */
    private fun mergeWithLegacyPriority(
        legacyTrack: Track,
        fallbackTrack: Track,
        mergedExtras: Map<String, String>
    ): Track {
        return legacyTrack.copy(
            extras = mergedExtras,
            streamables = legacyTrack.streamables.takeIf { it.isNotEmpty() } 
                ?: createDefaultStreamable(mergedExtras["videoId"]!!)
        )
    }
    
    /**
     * Create fallback track when both API calls fail.
     * Uses original track data with enhanced streamables.
     */
    private fun createFallbackTrack(
        fallbackTrack: Track,
        mergedExtras: Map<String, String>,
        trackId: String
    ): Track {
        return fallbackTrack.copy(
            extras = mergedExtras,
            streamables = fallbackTrack.streamables.takeIf { it.isNotEmpty() }
                ?: createDefaultStreamable(trackId)
        )
    }
    
    /**
     * Create default streamable configuration.
     */
    private fun createDefaultStreamable(videoId: String): List<Streamable> {
        return listOf(
            Streamable.server(
                id = "youtube_music_$videoId",
                quality = 128,
                title = "YouTube Music",
                extras = mapOf("videoId" to videoId)
            )
        )
    }
}
