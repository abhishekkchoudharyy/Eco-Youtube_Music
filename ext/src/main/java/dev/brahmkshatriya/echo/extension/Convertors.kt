package dev.brahmkshatriya.echo.extension

import sh.syk.kmpresources.library.model.Locale
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Date
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.Track.Type
import dev.brahmkshatriya.echo.common.models.Track.Playable
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.extension.YoutubeExtension.Companion.ENGLISH
import dev.brahmkshatriya.echo.extension.YoutubeExtension.Companion.SINGLES
import dev.brahmkshatriya.echo.extension.endpoints.GoogleAccountResponse
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.model.external.ThumbnailProvider
import dev.toastbits.ytmkt.model.external.mediaitem.MediaItemLayout
import dev.toastbits.ytmkt.model.external.mediaitem.YtmArtist
import dev.toastbits.ytmkt.model.external.mediaitem.YtmMediaItem
import dev.toastbits.ytmkt.model.external.mediaitem.YtmPlaylist
import dev.toastbits.ytmkt.model.external.mediaitem.YtmSong
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json

fun String.toDate(): Date = Date(this.toInt())

fun String.toDateFromTimestamp(): Date = Date(this.toLong())

fun String.containsTimestamp(): Boolean {
    return Regex("\\d{10,}").containsMatchIn(this)
}

suspend fun MediaItemLayout.toShelf(
    api: YoutubeiApi,
    language: String,
    quality: ThumbnailProvider.Quality
): Shelf {
    val single = title?.get(Locale.parse(ENGLISH)) == SINGLES
    return try {
        Shelf.Lists.Items(
            id = title?.get(Locale.parse(language))?.hashCode()?.toString() ?: "Unknown",
            title = title?.get(Locale.parse(language)) ?: "Unknown",
            subtitle = subtitle?.get(Locale.parse(language)),
            list = items.mapNotNull { item ->
                try {
                    item.toEchoMediaItem(single, quality)
                } catch (e: Exception) {
                    println("Failed to convert media item in shelf: ${e.message}")
                    null
                }
            },
            more = view_more?.getBrowseParamsData()?.browseId?.let { id ->
                if (id.startsWith("FEmusic_")) {
                    println("Skipping view more for special browse_id: $id")
                    return@let null
                }
                
                val pagedData = PagedData.Single<EchoMediaItem> {
                    try {
                        println("Loading view more page for browse_id: $id, visitor_id: ${api.visitorId}")
                        val rows =
                            api.GenericFeedViewMorePage.getGenericFeedViewMorePage(id).getOrThrow()
                        println("Got ${rows.size} items from view more page")
                        rows.mapNotNull { ytmItem ->
                            try {
                                ytmItem.toEchoMediaItem(single, quality)
                            } catch (e: Exception) {
                                println("Failed to convert media item in generic feed: ${e.message}")
                                e.printStackTrace()
                                null
                            }
                        }
                    } catch (e: NotImplementedError) {
                        println("Generic feed view more page not implemented for ID: $id")
                        e.printStackTrace()
                        emptyList()
                    } catch (e: NullPointerException) {
                        println("Null pointer in generic feed view more page for ID: $id")
                        e.printStackTrace()
                        emptyList()
                    } catch (e: Exception) {
                        println("Error loading generic feed view more page for ID: $id: ${e.message}")
                        e.printStackTrace()
                        emptyList()
                    }
                }
                Feed(emptyList()) { _ ->
                    Feed.Data(PagedData.Single<Shelf> {
                        try {
                            pagedData.loadAll().map { Shelf.Item(it) }
                        } catch (e: Exception) {
                            println("Failed to load paged data for shelf: ${e.message}")
                            emptyList()
                        }
                    })
                }
            }
        )
    } catch (e: Exception) {
        println("Failed to create shelf: ${e.message}")
        Shelf.Lists.Items(
            id = "error-${System.currentTimeMillis()}",
            title = "Content unavailable",
            subtitle = "Unable to load this content",
            list = emptyList(),
            more = null
        )
    }
}

fun YtmMediaItem.toEchoMediaItem(
    single: Boolean,
    quality: ThumbnailProvider.Quality
): EchoMediaItem? {
    return try {
        when (this) {
            is YtmSong -> toTrack(quality)
            is YtmPlaylist -> when (type) {
                YtmPlaylist.Type.ALBUM -> toAlbum(single, quality)
                else -> {
                    if (id != "VLSE") toPlaylist(quality)
                    else null
                }
            }
            is YtmArtist -> toArtist(quality)
            else -> null
        }
    } catch (e: Exception) {
        println("Failed to convert YtmMediaItem to EchoMediaItem: ${e.message}")
        null
    }
}

fun YtmPlaylist.toPlaylist(
    quality: ThumbnailProvider.Quality, related: String? = null
): Playlist {
    return try {
        val extras = mutableMapOf<String, String>()
        related?.let { extras["relatedId"] = it }
        val bool = owner_id?.split(",")?.map {
            it.toBoolean()
        } ?: listOf(false, false)
        Playlist(
            id = id,
            title = name ?: "Unknown",
            isEditable = bool.getOrNull(1) ?: false,
            cover = thumbnail_provider?.getThumbnailUrl(quality)?.toImageHolder(mapOf()),
            authors = artists?.map { it.toUser(quality) }?.let { ModelTypeHelper.safeArtistListConversion(it) } ?: emptyList(),
            trackCount = item_count?.toLong(),
            duration = total_duration?.toLong(),
            creationDate = year?.let { yearStr -> 
                parseYearString(yearStr)
            },
            description = description,
            extras = extras,
        )
    } catch (e: Exception) {
        println("Failed to convert YtmPlaylist to Playlist: ${e.message}")
        Playlist(
            id = id,
            title = name ?: "Unknown Playlist",
            isEditable = false,
            cover = null,
            authors = emptyList(),
            trackCount = null,
            duration = null,
            creationDate = null,
            description = null,
            extras = emptyMap()
        )
    }
}

fun YtmPlaylist.toAlbum(
    single: Boolean = false,
    quality: ThumbnailProvider.Quality
): Album {
    return try {
        val bool = owner_id?.split(",")?.map {
            it.toBoolean()
        } ?: listOf(false, false)
        Album(
            id = id,
            title = name ?: "Unknown",
            isExplicit = bool.firstOrNull() ?: false,
            cover = thumbnail_provider?.getThumbnailUrl(quality)?.toImageHolder(mapOf()),
            artists = artists?.map { it.toArtist(quality) } ?: emptyList(),
            trackCount = item_count?.toLong() ?: if (single) 1L else null,
            releaseDate = year?.let { yearStr -> 
                parseYearString(yearStr)
            },
            label = null,
            duration = total_duration?.toLong(),
            description = description,
        )
    } catch (e: Exception) {
        println("Failed to convert YtmPlaylist to Album: ${e.message}")
        Album(
            id = id,
            title = name ?: "Unknown Album",
            isExplicit = false,
            cover = null,
            artists = emptyList(),
            trackCount = if (single) 1L else null,
            releaseDate = null,
            label = null,
            duration = null,
            description = null,
        )
    }
}

fun YtmSong.toTrack(
    quality: ThumbnailProvider.Quality,
    setId: String? = null
): Track {
    return try {
        val album = album?.toAlbum(false, quality)
        val extras = mutableMapOf<String, String>()
        setId?.let { extras["setId"] = it }
        
        val trackType = Track.Type.Song
        
        val playableStatus = Track.Playable.Yes
        
        Track(
            id = id,
            title = name ?: "Unknown",
            type = trackType,
            artists = artists?.map { it.toArtist(quality) } ?: emptyList(),
            cover = thumbnail_provider?.getThumbnailUrl(quality)?.toImageHolder(crop = true)
                ?: getCover(id, quality),
            album = album,
            duration = duration?.toLong(),
            plays = null,  
            releaseDate = album?.releaseDate,
            isExplicit = is_explicit,
            isPlayable = playableStatus,
            isRadioSupported = true,
            isFollowable = false,
            isSaveable = true,
            isLikeable = true,
            isHideable = true,
            isShareable = true,
            extras = extras.apply {
                put("videoId", id)
                put("availability", "public")
                put("trackType", trackType.name)
            }
        )
    } catch (e: Exception) {
        println("Failed to convert YtmSong to Track: ${e.message}")
        Track(
            id = id,
            title = name ?: "Unknown Track",
            type = Track.Type.Song,
            artists = emptyList(),
            cover = getCover(id, quality),
            album = null,
            duration = null,
            plays = null,
            releaseDate = null,
            isExplicit = false,
            isPlayable = Track.Playable.Yes,
            isRadioSupported = true,
            isFollowable = false,
            isSaveable = true,
            isLikeable = true,
            isHideable = true,
            isShareable = true,
            extras = mapOf("videoId" to id, "availability" to "public", "trackType" to "SONG")
        )
    }
}

private fun getCover(
    id: String,
    quality: ThumbnailProvider.Quality
): ImageHolder.NetworkRequestImageHolder {
    return when (quality) {
        ThumbnailProvider.Quality.LOW -> "https://img.youtube.com/vi/$id/mqdefault.jpg"
        ThumbnailProvider.Quality.HIGH -> "https://img.youtube.com/vi/$id/maxresdefault.jpg"
    }.toImageHolder(crop = true)
}

fun YtmArtist.toArtist(
    quality: ThumbnailProvider.Quality,
): Artist {
    return try {
        Artist(
            id = id,
            name = name ?: "Unknown",
            cover = thumbnail_provider?.getThumbnailUrl(quality)?.toImageHolder(mapOf()),
            bio = description,
            extras = mutableMapOf<String, String>().apply {
                subscribe_channel_id?.let { put("subId", it) }
                subscriber_count?.let { put("subscriberCount", it.toString()) }
                subscribed?.let { put("isSubscribed", it.toString()) }
                put("genuineArtist", "true")
            }
        )
    } catch (e: Exception) {
        println("Failed to convert YtmArtist to Artist: ${e.message}")
        Artist(
            id = id,
            name = name ?: "Unknown Artist",
            cover = null,
            bio = null,
            extras = mapOf("genuineArtist" to "true")
        )
    }
}

fun YtmArtist.toUser(
    quality: ThumbnailProvider.Quality,
): User {
    return try {
        val subscriberCountText = subscriber_count?.let { count ->
            when {
                count >= 1000000 -> "${(count / 1000000).toInt()}M subscribers"
                count >= 1000 -> "${(count / 1000).toInt()}K subscribers"
                else -> "$count subscribers"
            }
        } ?: "Artist"
        
        User(
            id = id,
            name = name ?: "Unknown",
            cover = thumbnail_provider?.getThumbnailUrl(quality)?.toImageHolder(mapOf()),
            subtitle = subscriberCountText,
            extras = mutableMapOf<String, String>().apply {
                subscribe_channel_id?.let { put("subId", it) }
                subscriber_count?.let { put("subscriberCount", it.toString()) }
                subscribed?.let { put("isSubscribed", it.toString()) }
                put("isArtist", "true")
                put("userType", "artist")
                put("profileUrl", "https://music.youtube.com/channel/$id")
                put("channelId", id)
                put("displayName", name ?: "Unknown")
            }
        )
    } catch (e: Exception) {
        println("Failed to convert YtmArtist to User: ${e.message}")
        User(
            id = id,
            name = name ?: "Unknown User",
            cover = null,
            subtitle = "Artist",
            extras = mapOf("isArtist" to "true", "userType" to "artist")
        )
    }
}

fun User.toArtist(): Artist {
    return try {
        ModelTypeHelper.userToArtist(this)
    } catch (e: Exception) {
        println("Failed to convert User to Artist: ${e.message}")
        Artist(
            id = id,
            name = name ?: "Unknown Artist",
            cover = cover,
            bio = subtitle,
            extras = extras.toMutableMap().apply { put("genuineArtist", "true") }
        )
    }
}

private fun parseYearString(yearValue: Any): Date? {
    val yearStr = yearValue.toString()
    return try {
        if (yearStr.length >= 10 && yearStr.all { it.isDigit() }) {
            Date(yearStr.toLong())
        } else {
            Date(yearStr.toInt())
        }
    } catch (e: Exception) {
        try {
            Date(yearStr.toInt())
        } catch (e: Exception) {
            null
        }
    }
}

val json = Json { ignoreUnknownKeys = true }
suspend fun HttpResponse.getUsers(
    cookie: String,
    auth: String
) = bodyAsText().let {
    val trimmed = it.substringAfter(")]}'")
    json.decodeFromString<GoogleAccountResponse>(trimmed)
}.getUsers(cookie, auth)