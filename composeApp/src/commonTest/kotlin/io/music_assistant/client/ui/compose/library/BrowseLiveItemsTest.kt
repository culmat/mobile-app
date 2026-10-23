package io.music_assistant.client.ui.compose.library

import io.music_assistant.client.data.model.client.Metadata
import io.music_assistant.client.data.model.client.items.RadioStation
import io.music_assistant.client.data.model.server.ProviderMapping
import io.music_assistant.client.data.repository.MediaItemChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An open browse level follows the server's item updates, so a radio station row shows the song
 * on air, and marks the item the selected player plays.
 */
class BrowseLiveItemsTest {
    private fun station(
        itemId: String,
        provider: String = "fip",
        description: String? = null,
        favorite: Boolean? = null,
        mappings: List<ProviderMapping> = listOf(ProviderMapping(itemId, "fip", "fip")),
    ) = RadioStation(
        itemId = itemId,
        provider = provider,
        name = itemId,
        providerMappings = mappings,
        metadata = Metadata(
            explicit = false,
            images = emptyList(),
            releaseDate = null,
            chapters = emptyList(),
            lyrics = null,
            lrcLyrics = null,
            description = description,
        ),
        favorite = favorite,
        uri = "$provider://radio/$itemId",
        images = emptyMap(),
        version = null,
        isPlayable = true,
        isDynamic = false,
    )

    // The library item FIP Groove was added as.
    private val libraryGroove = station(
        itemId = "3",
        provider = "library",
        mappings = listOf(ProviderMapping("fip_groove", "fip", "fip")),
    )

    @Test
    fun `an update replaces the entry of the updated item and leaves the others`() {
        val items = listOf(station("fip_groove", description = "Molly"), station("fip_jazz", description = "Dizzy"))

        val updated = items.updatedBy(MediaItemChange.Updated(station("fip_groove", description = "Butterflies")))

        assertEquals(listOf("Butterflies", "Dizzy"), updated.map { it.subtitle })
    }

    @Test
    fun `an update without the user's favorite keeps the entry's favorite`() {
        val items = listOf(station("fip_groove", favorite = true))

        val updated = items.updatedBy(
            MediaItemChange.Updated(station("fip_groove", description = "Butterflies"), carriesFavorite = false),
        )

        assertEquals(true, updated.single().favorite)
        assertEquals("Butterflies", updated.single().subtitle)
    }

    @Test
    fun `an update of the library item does not replace the provider's entry`() {
        val items = listOf(station("fip_groove", description = "Molly"))

        assertEquals(items, items.updatedBy(MediaItemChange.Updated(libraryGroove)))
    }

    @Test
    fun `an entry is marked when it plays itself or as the library item it was added as`() {
        val groove = station("fip_groove")

        assertTrue(groove.isPlayedAs(station("fip_groove", description = "Molly")))
        assertTrue(groove.isPlayedAs(libraryGroove))
        assertFalse(groove.isPlayedAs(station("fip_jazz")))
        assertFalse(groove.isPlayedAs(null))
    }
}
