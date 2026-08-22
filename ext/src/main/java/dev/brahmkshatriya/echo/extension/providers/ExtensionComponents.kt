package dev.brahmkshatriya.echo.extension.providers

import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.YoutubeExtension
import dev.brahmkshatriya.echo.extension.auth.YouTubeAuthManager
import dev.brahmkshatriya.echo.extension.endpoints.*
import dev.brahmkshatriya.echo.extension.providers.feeds.LibraryFeedProvider
import dev.brahmkshatriya.echo.extension.providers.feeds.SearchFeedProvider
import dev.brahmkshatriya.echo.extension.providers.playback.RadioGenerator
import dev.brahmkshatriya.echo.extension.providers.playback.TrackLoader
import dev.brahmkshatriya.echo.extension.providers.playlists.PlaylistManager
import dev.brahmkshatriya.echo.extension.providers.social.FollowManager
import dev.brahmkshatriya.echo.extension.providers.social.LikeManager
import dev.brahmkshatriya.echo.extension.providers.social.SaveManager
import dev.brahmkshatriya.echo.extension.providers.social.ShareManager
import dev.brahmkshatriya.echo.extension.search.YouTubeSearchService
import dev.brahmkshatriya.echo.extension.streaming.YouTubeStreamResolver
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.model.external.ThumbnailProvider
import kotlinx.serialization.json.Json

//Central hub for creating and managing all extension components.

class ExtensionComponents(
    private val api: YoutubeiApi,
    private val settings: Settings,
    private val json: Json
) {
    //Core Settings
    
    val thumbnailQuality: ThumbnailProvider.Quality
        get() = if (settings.getBoolean("high_quality") == true) {
            ThumbnailProvider.Quality.HIGH
        } else {
            ThumbnailProvider.Quality.LOW
        }

    val preferVideos: Boolean
        get() = settings.getBoolean("prefer_videos") == true

    val maxVideoQuality: Int
        get() = settings.getString("video_quality")?.toIntOrNull() ?: 480  

    //Endpoints (API Communication)
    
    val visitorEndpoint = EchoVisitorEndpoint(api)
    val videoEndpoint = EchoVideoEndpoint(api)
    val songFeedEndpoint = EchoSongFeedEndpoint(api)
    val artistEndpoint = EchoArtistEndpoint(api)
    val artistMoreEndpoint = EchoArtistMoreEndpoint(api)
    val libraryEndpoint = EchoLibraryEndPoint(api)
    val songEndpoint = EchoSongEndPoint(api)
    val songRelatedEndpoint = EchoSongRelatedEndpoint(api)
    val playlistEndpoint = EchoPlaylistEndpoint(api)
    val lyricsEndpoint = EchoLyricsEndPoint(api)
    val searchSuggestionsEndpoint = EchoSearchSuggestionsEndpoint(api)
    val searchEndpoint = EchoSearchEndpoint(api)
    val editorEndpoint = EchoEditPlaylistEndpoint(api)
    
    val enhancedSongEndpoint by lazy {
        EchoEnhancedSongEndpoint(api, songEndpoint)
    }

    //Services
    
    val authManager by lazy {
        YouTubeAuthManager(api, visitorEndpoint)
    }

    val streamResolver by lazy {
        YouTubeStreamResolver(api, settings, videoEndpoint, visitorEndpoint)
    }

    val searchService by lazy {
        YouTubeSearchService(api)
    }

    //providers

    val libraryFeedProvider by lazy {
        LibraryFeedProvider(api, authManager, libraryEndpoint)
    }

    val searchFeedProvider by lazy {
        SearchFeedProvider(api, searchService, songFeedEndpoint)
    }

    val trackLoader by lazy {
        TrackLoader(authManager, enhancedSongEndpoint, streamResolver)
    }

    val radioGenerator by lazy {
        RadioGenerator(api, json, thumbnailQuality, trackCache)
    }

    val playlistManager by lazy {
        PlaylistManager(authManager, editorEndpoint, thumbnailQuality)
    }

    val saveManager by lazy {
        SaveManager(authManager, playlistEndpoint, thumbnailQuality)
    }

    val likeManager by lazy {
        LikeManager(authManager)
    }

    val followManager by lazy {
        FollowManager(authManager)
    }

    val shareManager = ShareManager()
    
    // Track cache for album/playlist tracks
    val trackCache = mutableMapOf<String, PagedData<Track>>()
}
