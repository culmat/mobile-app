package io.music_assistant.client.data.model.server

import io.music_assistant.client.data.factory.MediaItemFactory
import io.music_assistant.client.data.model.client.MediaType
import io.music_assistant.client.data.model.client.items.RadioStation
import io.music_assistant.client.utils.myJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A radio station's subtitle is its description, as in the web app's lists. A provider with live
 * data names what is on air there, so a station row shows the song instead of just "Radio".
 */
class RadioDescriptionMappingTest {
    private val factory = MediaItemFactory(StubServiceClient())

    private fun radio(description: String?): RadioStation {
        val json = buildString {
            append("""{"item_id":"fip_groove","provider":"fip","media_type":"${MediaType.RADIO.serverValue}"""")
            append(""","name":"FIP Groove"""")
            description?.let { append(""","metadata":{"description":"$it"}""") }
            append("}")
        }
        return factory.create(myJson.decodeFromString<ServerMediaItem>(json)) as RadioStation
    }

    @Test
    fun `the description is carried onto the station and shown as its subtitle`() {
        val station = radio(description = "Molly — Jungle")

        assertEquals("Molly — Jungle", station.metadata?.description)
        assertEquals("Molly — Jungle", station.subtitle)
    }

    @Test
    fun `a station without a description has no subtitle of its own`() {
        assertNull(radio(description = null).subtitle)
        assertNull(radio(description = " ").subtitle)
    }
}
