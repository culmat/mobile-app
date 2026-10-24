package io.music_assistant.client.data.model.server

import io.music_assistant.client.data.factory.PlayerFactory
import io.music_assistant.client.data.model.client.SongTiming
import io.music_assistant.client.utils.myJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A radio stream that knows its song reports the song's duration and the position in it; the
 * full player times its slider by that song instead of by how long the station has played.
 */
class RadioSongTimingTest {
    private val playerFactory = PlayerFactory(StubServiceClient())

    private fun songTiming(mediaType: String, timing: String) = playerFactory.create(
        myJson.decodeFromString<ServerPlayer>(
            """{"player_id": "pl1", "current_media": {"media_type": "$mediaType", "title": "Molly"$timing}}""",
        ),
    ).currentMedia?.songTiming

    @Test
    fun `a radio stream reporting its song carries the song's timing`() {
        val timing = songTiming(
            "radio",
            """, "duration": 430, "elapsed_time": 100, "elapsed_time_last_updated": 1000""",
        )

        assertEquals(SongTiming(duration = 430.0, elapsed = 100.0, updatedAt = 1000.0), timing)
    }

    @Test
    fun `neither a radio stream without song durations nor a track carries any`() {
        assertNull(songTiming("radio", """, "elapsed_time": 1289, "elapsed_time_last_updated": 1000"""))
        assertNull(
            songTiming("track", """, "duration": 200, "elapsed_time": 10, "elapsed_time_last_updated": 1000"""),
        )
    }

    @Test
    fun `the song position moves with the clock and stops at the song's end`() {
        val timing = SongTiming(duration = 430.0, elapsed = 100.0, updatedAt = 1000.0)

        assertEquals(104.0, timing.positionAt(1004.0))
        assertEquals(430.0, timing.positionAt(5000.0))
    }
}
