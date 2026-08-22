package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.extension.endpoints.EchoEnhancedSongEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline unit tests for the artist-list merge logic that works around the
 * ytm-kt 0.6.x nameless-artist regression (channel id present, name NULL,
 * rendered as the literal string "Unknown").
 */
class ArtistMergeTest {

    private fun artist(name: String?) = Artist(
        id = "id-${name ?: "null"}",
        name = name ?: "Unknown",
        cover = null,
        extras = mutableMapOf()
    )

    private val named = listOf(artist("Artist A"), artist("Artist B"))
    private val partiallyUnknown = listOf(artist("Unknown"), artist("Artist B"))
    private val allUnknown = listOf(artist("Unknown"), artist("UNKNOWN"))

    @Test
    fun `prefers fully named list over partially unknown list`() {
        val picked = EchoEnhancedSongEndpoint.pickBestArtists(partiallyUnknown, named)
        assertEquals(named, picked)
    }

    @Test
    fun `skips lists with null or blank names`() {
        val picked = EchoEnhancedSongEndpoint.pickBestArtists(allUnknown, named)
        assertEquals(named, picked)
    }

    @Test
    fun `returns fully named list when it is the only candidate`() {
        val picked = EchoEnhancedSongEndpoint.pickBestArtists(named)
        assertEquals(named, picked)
    }

    @Test
    fun `falls back to first non-empty list when no list is fully named`() {
        val picked = EchoEnhancedSongEndpoint.pickBestArtists(allUnknown)
        assertEquals(allUnknown, picked)
    }

    @Test
    fun `handles case-insensitive unknown and blank names`() {
        assertTrue(!EchoEnhancedSongEndpoint.isValidArtistName(artist("UNKNOWN")))
        assertTrue(!EchoEnhancedSongEndpoint.isValidArtistName(artist(" ")))
        assertTrue(EchoEnhancedSongEndpoint.isValidArtistName(artist("Real Name")))
    }

    @Test
    fun `returns empty list when no candidates`() {
        assertTrue(EchoEnhancedSongEndpoint.pickBestArtists(null, emptyList()).isEmpty())
    }
}
