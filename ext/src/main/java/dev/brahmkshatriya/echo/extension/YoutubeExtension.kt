package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.FollowClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.LikeClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.clients.LyricsSearchClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.PlaylistEditClient
import dev.brahmkshatriya.echo.common.clients.QuickSearchClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.SaveClient
import dev.brahmkshatriya.echo.common.clients.ShareClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.clients.TrackerClient
import dev.brahmkshatriya.echo.common.clients.TrackerMarkClient
import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.helpers.WebViewRequest
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.loadAll
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.Feed.Companion.pagedDataOfFirst
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.NetworkRequest.Companion.toGetRequest
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.QuickSearchItem
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toMedia
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.Track.Type
import dev.brahmkshatriya.echo.common.models.Track.Playable
import dev.brahmkshatriya.echo.common.models.TrackDetails
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingList
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.endpoints.EchoArtistEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoArtistMoreEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.GoogleAccountResponse
import dev.brahmkshatriya.echo.extension.utils.CookieParser
import dev.brahmkshatriya.echo.extension.providers.ExtensionComponents
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import sh.syk.kmpresources.library.model.Locale
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiAuthenticationState
import dev.toastbits.ytmkt.model.external.PlaylistEditor
import dev.toastbits.ytmkt.model.external.SongLikedStatus
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import dev.toastbits.ytmkt.model.external.ThumbnailProvider.Quality.HIGH
import dev.toastbits.ytmkt.model.external.ThumbnailProvider.Quality.LOW
import dev.toastbits.ytmkt.model.external.mediaitem.YtmArtist
import dev.toastbits.ytmkt.model.external.mediaitem.YtmPlaylist
import dev.toastbits.ytmkt.model.external.mediaitem.YtmSong
import dev.toastbits.ytmkt.model.external.YoutubeVideoFormat
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.http.headers
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.encodeToString
import java.security.MessageDigest
import dev.toastbits.ytmkt.endpoint.SearchResults
import dev.toastbits.ytmkt.endpoint.SearchType

private fun createShelfPagedDataFromMediaItems(mediaItems: PagedData<EchoMediaItem>): PagedData<Shelf> {
    return PagedData.Continuous { continuation ->
        val page = mediaItems.loadPage(continuation)
        val shelves = page.data.map { item -> Shelf.Item(item) }
        Page(shelves, page.continuation)
    }
}
class YoutubeExtension : ExtensionClient, HomeFeedClient, TrackClient, SearchFeedClient,
    RadioClient, AlbumClient, ArtistClient, PlaylistClient, LoginClient.WebView,
    TrackerClient, TrackerMarkClient, LibraryFeedClient, ShareClient, LyricsClient, FollowClient,
    LikeClient, PlaylistEditClient, LyricsSearchClient, QuickSearchClient,
    SaveClient {

    override suspend fun getSettingItems(): List<Setting> = listOf(
        SettingSwitch(
            "High Thumbnail Quality",
            "high_quality",
            "Use high quality thumbnails, will cause more data usage.",
            true
        ),
        SettingSwitch(
            "Prefer Videos",
            "prefer_videos",
            "Prefer videos over audio when available.",
            false
        ),
        SettingList(
            "Video Quality [Most of Time only 360p is available]",
            "video_quality",
            "Maximum video quality for playback. Higher quality uses more data and may buffer more.",
            entryTitles = listOf("360p", "480p", "720p", "Best Available"),
            entryValues = listOf("360", "480", "720", "999999"),
            defaultEntryIndex = 1  
        )
    )

    private lateinit var settings: Settings
    override fun setSettings(settings: Settings) {
        this.settings = settings
        components = ExtensionComponents(api, settings, json)
    }

    val api = YoutubeiApi(
        dataLocale = Locale.parse(ENGLISH)
    )

    private val language = ENGLISH
    
    private lateinit var components: ExtensionComponents
    
    private val artistEndPoint by lazy { components.artistEndpoint }
    private val artistMoreEndpoint by lazy { components.artistMoreEndpoint }
    private val songRelatedEndpoint by lazy { components.songRelatedEndpoint }
    private val lyricsEndPoint by lazy { components.lyricsEndpoint }
    private val searchSuggestionsEndpoint by lazy { components.searchSuggestionsEndpoint }
    private val playlistEndPoint by lazy { components.playlistEndpoint }
    private val songFeedEndPoint by lazy { components.songFeedEndpoint }
    
    private val thumbnailQuality
        get() = components.thumbnailQuality

    private val preferVideos
        get() = components.preferVideos

    companion object {
        const val ENGLISH = "en-GB"
        const val SINGLES = "Singles"
        const val SONGS = "songs"
    }
    override suspend fun loadHomeFeed(): Feed<Shelf> {
        val tabs = listOf<Tab>()
        return Feed(tabs) { tab ->
            val pagedData = PagedData.Continuous { continuation ->
                val result = songFeedEndPoint.getSongFeed(
                    params = tab?.id, continuation = continuation
                ).getOrThrow()
                val data = result.layouts.map { itemLayout ->
                    itemLayout.toShelf(api, SINGLES, thumbnailQuality)
                }
                Page(data, result.ctoken)
            }
            Feed.Data(pagedData)
        }
    }
    override suspend fun loadStreamableMedia(
        streamable: Streamable, isDownload: Boolean
    ): Streamable.Media {
        return components.trackLoader.loadStreamableMedia(streamable, preferVideos)
    }
    
   override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        return components.trackLoader.loadTrackDetails(track, thumbnailQuality)
    }

    private suspend fun loadRelated(track: Track): List<Shelf> {
        val relatedId = track.extras["relatedId"]
        return if (relatedId != null) {
            try {
                songFeedEndPoint.getSongFeed(browseId = relatedId).getOrThrow().layouts.map {
                    it.toShelf(api, SINGLES, thumbnailQuality)
                }
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? {
        val shelves = loadRelated(track)
        return if (shelves.isNotEmpty()) {
            Feed(emptyList()) { _ -> PagedData.Single { shelves }.toFeedData() }
        } else {
            null
        }
    }

    override suspend fun deleteQuickSearch(item: QuickSearchItem) {
        searchSuggestionsEndpoint.delete(item as QuickSearchItem.Query)
    }

    override suspend fun quickSearch(query: String) = query.takeIf { it.isNotBlank() }?.run {
        try {
            api.SearchSuggestions.getSearchSuggestions(this).getOrThrow()
                .map { QuickSearchItem.Query(it.text, it.is_from_history) }
        } catch (e: NullPointerException) {
            null
        } catch (e: ConnectTimeoutException) {
            null
        }
    } ?: listOf()


    private var oldSearch: Pair<String, List<Shelf>>? = null
    
    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        return components.searchFeedProvider.loadSearchFeed(query, thumbnailQuality)
    }
    
    override suspend fun loadTracks(radio: Radio): Feed<Track> =
        components.radioGenerator.loadRadioTracks(radio)

    suspend fun radio(album: Album): Radio {
        return components.radioGenerator.generateRadio(album)
    }

    suspend fun radio(artist: Artist): Radio {
        return components.radioGenerator.generateRadio(artist)
    }

    suspend fun radio(track: Track, context: EchoMediaItem? = null): Radio {
        return components.radioGenerator.generateRadio(track, context)
    }

    suspend fun radio(user: User): Radio {
        val artist = ModelTypeHelper.userToArtist(user)
        return components.radioGenerator.generateRadio(artist)
    }

    suspend fun radio(playlist: Playlist): Radio {
        return components.radioGenerator.generateRadio(playlist)
    }

    override suspend fun loadFeed(album: Album): Feed<Shelf>? {
        val tracks = loadTracks(album)?.loadAll() ?: emptyList()
        val lastTrack = tracks.lastOrNull() ?: return null
        val loadedTrack = loadTrack(lastTrack, false)
        val shelves = loadRelated(loadedTrack)
        return Feed(emptyList()) { _ -> PagedData.Single { shelves }.toFeedData() }
    }


    private val trackMap get() = components.trackCache
    override suspend fun loadAlbum(album: Album): Album {
        val (ytmPlaylist, _, data) = playlistEndPoint.loadFromPlaylist(
            album.id, null, thumbnailQuality
        )
        trackMap[ytmPlaylist.id] = data
        return ytmPlaylist.toAlbum(false, HIGH)
    }

    override suspend fun loadTracks(album: Album): Feed<Track>? = trackMap[album.id]?.toFeed()

    private suspend fun getArtistMediaItems(artist: Artist): List<Shelf> {
        val result =
            loadedArtist.takeIf { artist.id == it?.id } ?: api.LoadArtist.loadArtist(artist.id)
                .getOrThrow()

        return result.layouts?.map {
            val title = it.title?.get(Locale.parse(ENGLISH))
            val single = title == SINGLES
            Shelf.Lists.Items(
                id = it.title?.get(Locale.parse(language))?.hashCode()?.toString() ?: "Unknown",
                title = it.title?.get(Locale.parse(language)) ?: "Unknown",
                subtitle = it.subtitle?.get(Locale.parse(language)),
                list = it.items?.mapNotNull { item ->
                    item.toEchoMediaItem(single, thumbnailQuality)
                } ?: emptyList(),
                more = it.view_more?.getBrowseParamsData()?.let { param ->
                    PagedData.Single {
                        val data = artistMoreEndpoint.load(param)
                        data.map { row ->
                            row.items.mapNotNull { item ->
                                item.toEchoMediaItem(single, thumbnailQuality)
                            }
                        }.flatten()
                    }.let { mediaItems ->
                        Feed(listOf()) { _ -> 
                            Feed.Data(createShelfPagedDataFromMediaItems(mediaItems))
                        }
                    }
                })
        } ?: emptyList()
    }

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> {
        val shelves = getArtistMediaItems(artist)
        return Feed(emptyList()) { _ -> PagedData.Single { shelves }.toFeedData() }
    }

    private var loadedArtist: YtmArtist? = null
    override suspend fun loadArtist(artist: Artist): Artist {
        val result = artistEndPoint.loadArtist(artist.id)
        loadedArtist = result
        return result.toArtist(HIGH)
    }

    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? {
        val cont = playlist.extras["relatedId"] ?: return null
        
        val shelves = try {
            if (cont.startsWith("id://")) {
                val id = cont.substring(5)
                val track = Track(id, "")
                val loadedTrack = loadTrack(track, false)
                val feed = loadFeed(loadedTrack)
                coroutineScope { 
                    if (feed != null) {
                        val items = feed.loadAll()
                        items.filterIsInstance<Shelf.Category>()
                    } else emptyList()
                }
            } else {
                songRelatedEndpoint.loadFromPlaylist(cont).getOrNull()?.map { 
                    it.toShelf(api, language, thumbnailQuality) 
                } ?: emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
        
        return if (shelves.isNotEmpty()) {
            Feed(emptyList()) { _ -> Feed.Data(PagedData.Single { shelves }) }
        } else {
            null
        }
    }


    override suspend fun loadPlaylist(playlist: Playlist): Playlist {
        val (ytmPlaylist, related, data) = playlistEndPoint.loadFromPlaylist(
            playlist.id,
            null,
            thumbnailQuality
        )
        trackMap[ytmPlaylist.id] = data
        return ytmPlaylist.toPlaylist(HIGH, related)
    }

    override suspend fun loadTracks(playlist: Playlist): Feed<Track> = trackMap[playlist.id]?.toFeed() ?: listOf<Track>().toFeed()


    override val webViewRequest = object : WebViewRequest.Cookie<List<User>> {
        override val initialUrl =
            "https://accounts.google.com/v3/signin/identifier?dsh=S1527412391%3A1678373417598386&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Ddesktop%26hl%3Den-GB%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F%253Fcbrd%253D1%26feature%3D__FEATURE__&hl=en-GB&ifkv=AWnogHfK4OXI8X1zVlVjzzjybvICXS4ojnbvzpE4Gn_Pfddw7fs3ERdfk-q3tRimJuoXjfofz6wuzg&ltmpl=music&passive=true&service=youtube&uilel=3&flowName=GlifWebSignIn&flowEntry=ServiceLogin".toGetRequest()
        override val stopUrlRegex = "https://music\\.youtube\\.com/.*".toRegex()
        override suspend fun onStop(url: NetworkRequest, cookie: String): List<User> {
            val cookieMap = CookieParser.parse(cookie)
            val sapisid = CookieParser.getSapisid(cookieMap)
                ?: throw Exception("Login Failed SAPISID cookie not found or empty. Please try logging in again.")
            
            val auth = CookieParser.generateSapisidHash(sapisid)
            val headersMap = mutableMapOf("cookie" to cookie, "authorization" to auth)
            val headers = headers { headersMap.forEach { (t, u) -> append(t, u) } }
            return api.client.request("https://music.youtube.com/getAccountSwitcherEndpoint") {
                headers {
                    append("referer", "https://music.youtube.com/")
                    appendAll(headers)
                }
            }.getUsers(cookie, auth)
        }
    }

    override fun setLoginUser(user: User?) {
        if (user == null) {
            api.userAuthState = null
        } else {
            val cookie = user.extras["cookie"] ?: throw Exception("No cookie")
            val auth = user.extras["auth"] ?: throw Exception("No auth")

            val headers = io.ktor.http.headers {
                append("cookie", cookie)
                append("authorization", auth)
            }
            val authenticationState =
                dev.toastbits.ytmkt.impl.youtubei.YoutubeiAuthenticationState(api, headers, user.id.ifEmpty { null })
            api.userAuthState = authenticationState
        }
        api.visitorId = runCatching { kotlinx.coroutines.runBlocking { components.visitorEndpoint.getVisitorId() } }.getOrNull()
    }

    override suspend fun getCurrentUser(): User? {
        val auth_headers = api.userAuthState?.headers ?: return null
        return runCatching {
            val response = api.client.request("https://music.youtube.com/getAccountSwitcherEndpoint") {
                headers {
                    append("referer", "https://music.youtube.com/")
                    appendAll(auth_headers)
                }
            }
            
            val responseText = response.bodyAsText()
            val jsonText = if (responseText.startsWith(")]}'")) {
                responseText.substringAfter(")]}'")
            } else {
                responseText
            }
            
            val accountResponse = json.decodeFromString<dev.brahmkshatriya.echo.extension.endpoints.GoogleAccountResponse>(jsonText)
            val userResponse = accountResponse.getUsers("", "").firstOrNull() ?: return@runCatching null
            
            userResponse.copy(
                subtitle = userResponse.extras["email"] ?: "YouTube Music User",
                extras = userResponse.extras.toMutableMap().apply {
                    put("isLoggedIn", "true")
                    put("userService", "youtube_music")
                    put("accountType", "google")
                    put("lastUpdated", System.currentTimeMillis().toString())
                }
            )
        }.getOrNull()
    }


    override suspend fun getMarkAsPlayedDuration(details: TrackDetails): Long? = 30000L

    override suspend fun onMarkAsPlayed(details: TrackDetails) {
        val authState = api.userAuthState ?: return
        val endpoint = authState.MarkSongAsWatched ?: return
        try {
            val result = endpoint.markSongAsWatched(details.track.id)
            if (result.isFailure) {
                println("MarkSongAsWatched failed ${result.exceptionOrNull()?.message}")
            }
        } catch (e: Exception) {
            println("MarkSongAsWatched threw ${e.message}")
        }
    }

    // Keep this helper for backward compatibility with remaining code
    private suspend fun <T> withUserAuth(
        block: suspend (auth: YoutubeiAuthenticationState) -> T
    ): T {
        val state = components.authManager.requireAuth()
        return runCatching { block(state) }.getOrElse {
            if (it is ClientRequestException) {
                if (it.response.status.value == 401) {
                    val user = state.own_channel_id
                        ?: throw ClientException.LoginRequired()
                    throw ClientException.Unauthorized(user)
                }
            }
            throw it
        }
    }

    override suspend fun loadLibraryFeed(): Feed<Shelf> {
        return components.libraryFeedProvider.loadLibraryFeed(thumbnailQuality)
    }

    override suspend fun createPlaylist(title: String, description: String?): Playlist {
        return components.playlistManager.createPlaylist(title, description)
    }

    override suspend fun deletePlaylist(playlist: Playlist) {
        components.playlistManager.deletePlaylist(playlist)
    }

    override suspend fun likeItem(item: EchoMediaItem, shouldLike: Boolean) {
        components.likeManager.setLiked(item, shouldLike)
    }

    override suspend fun saveToLibrary(item: EchoMediaItem, shouldSave: Boolean) {
        components.saveManager.saveToLibrary(item, shouldSave)
    }

    override suspend fun isItemSaved(item: EchoMediaItem): Boolean {
        return runCatching { components.saveManager.isSaved(item) }.getOrNull() ?: false
    }

    private suspend fun likeTrack(track: Track, isLiked: Boolean) {
        components.likeManager.setLiked(track, isLiked)
    }

    override suspend fun listEditablePlaylists(track: Track?): List<Pair<Playlist, Boolean>> {
        return components.playlistManager.getEditablePlaylists(track)
    }

    override suspend fun editPlaylistMetadata(
        playlist: Playlist, title: String, description: String?
    ) {
        components.playlistManager.updatePlaylistMetadata(playlist, title, description)
    }

    override suspend fun removeTracksFromPlaylist(
        playlist: Playlist, tracks: List<Track>, indexes: List<Int>
    ) {
        components.playlistManager.removeTracks(playlist, tracks, indexes)
    }

    override suspend fun addTracksToPlaylist(
        playlist: Playlist, tracks: List<Track>, index: Int, new: List<Track>
    ) {
        components.playlistManager.addTracks(playlist, tracks, index, new)
    }

    override suspend fun moveTrackInPlaylist(
        playlist: Playlist, tracks: List<Track>, fromIndex: Int, toIndex: Int
    ) {
        components.playlistManager.moveTrack(playlist, tracks, fromIndex, toIndex)
    }

    override suspend fun searchTrackLyrics(clientId: String, track: Track): Feed<Lyrics> {
        val pagedData = PagedData.Single {
            val lyricsId = track.extras["lyricsId"] ?: return@Single listOf()
            val data = lyricsEndPoint.getLyrics(lyricsId) ?: return@Single listOf()
            val lyrics = data.first.map {
                it.cueRange.run {
                    Lyrics.Item(
                        it.lyricLine,
                        startTimeMilliseconds.toLong(),
                        endTimeMilliseconds.toLong()
                    )
                }
            }
            listOf(Lyrics(lyricsId, track.title, data.second, Lyrics.Timed(lyrics)))
        }
        return pagedData.toFeed()
    }

    override suspend fun loadLyrics(lyrics: Lyrics) = lyrics

    override suspend fun onShare(item: EchoMediaItem) = components.shareManager.getShareUrl(item)
    
    override suspend fun radio(item: EchoMediaItem, context: EchoMediaItem?): Radio {
        return components.radioGenerator.generateRadio(item, context)
    }
    
    override suspend fun loadRadio(radio: Radio): Radio = radio
    
    private fun String.toGetRequest(): NetworkRequest {
        return NetworkRequest(url = this)
    }
    
    override suspend fun onTrackChanged(details: TrackDetails?) {}
    
    override suspend fun onPlayingStateChanged(details: TrackDetails?, isPlaying: Boolean) {}
    
    override suspend fun isItemLiked(item: EchoMediaItem): Boolean {
        return components.likeManager.isLiked(item)
    }
    
    override suspend fun isFollowing(item: EchoMediaItem): Boolean {
        return components.followManager.isFollowing(item)
    }
    
    override suspend fun getFollowersCount(item: EchoMediaItem): Long? {
        return components.followManager.getFollowerCount(item)
    }
    
    override suspend fun followItem(item: EchoMediaItem, shouldFollow: Boolean) {
        components.followManager.setFollowing(item, shouldFollow)
    }
    
    override suspend fun searchLyrics(query: String): Feed<Lyrics> {
        return listOf<Lyrics>().toFeed()
    }
}