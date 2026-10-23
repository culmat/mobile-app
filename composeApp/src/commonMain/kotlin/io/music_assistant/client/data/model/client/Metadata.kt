package io.music_assistant.client.data.model.client

/**
 * Client-side metadata view of [io.music_assistant.client.data.model.server.ServerMetadata].
 *
 * Only carries fields the app actually reads; everything else the server sends
 * is dropped at the mapping boundary in `MediaItemFactory`.
 */
data class Metadata(
    val explicit: Boolean,
    val images: List<ImageInfo>,
    val releaseDate: String?,
    val chapters: List<Chapter>,
    // Embedded lyrics the server may ship with the item; when present they spare a
    // metadata/get_track_lyrics round-trip. [lrcLyrics] is timestamped LRC, [lyrics] plain.
    val lyrics: String?,
    val lrcLyrics: String?,
    // The item's description. A radio provider with live data names what is on air in it.
    val description: String? = null,
)
