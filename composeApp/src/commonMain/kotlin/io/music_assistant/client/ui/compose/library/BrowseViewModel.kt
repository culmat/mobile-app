package io.music_assistant.client.ui.compose.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import io.music_assistant.client.api.Request
import io.music_assistant.client.data.MainDataSource
import io.music_assistant.client.data.model.client.QueueOption
import io.music_assistant.client.data.model.client.byId
import io.music_assistant.client.data.model.client.items.AppMediaItem
import io.music_assistant.client.data.model.client.items.Genre
import io.music_assistant.client.data.play.PlayDispatcher
import io.music_assistant.client.data.play.PlayRequest
import io.music_assistant.client.data.repository.MediaItemChange
import io.music_assistant.client.data.repository.MediaItemRepository
import io.music_assistant.client.data.repository.favoriteAfter
import io.music_assistant.client.ui.compose.common.DataState
import io.music_assistant.client.ui.compose.common.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs one level of the folder-style Browse screen. [path] is the server browse path for this
 * level (null = provider root); a folder's [AppMediaItem.uri] becomes the next level's path.
 * The server returns the full level in one shot, so there's no pagination/sort/favorites here.
 */
class BrowseViewModel(
    private val path: String?,
    private val playDispatcher: PlayDispatcher,
    private val mainDataSource: MainDataSource,
    private val mediaItemRepository: MediaItemRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<DataState<List<AppMediaItem>>>(DataState.Loading())
    val state = _state.asStateFlow()

    /** What the selected player is playing, or null while it is not playing. */
    val nowPlaying: StateFlow<AppMediaItem?> =
        combine(mainDataSource.playersData, mainDataSource.selectedPlayerId) { players, selectedId ->
            selectedId?.let { players.dataOrNull?.byId(it) }
                ?.takeIf { it.player.isPlaying }
                ?.queueInfo?.currentItem?.track as? AppMediaItem
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        load()

        // A provider can change what a browsed item shows, a radio station the song on air,
        // say. Patch the open level in place, no refetch.
        viewModelScope.launch {
            mediaItemRepository.itemChanges.collect { change ->
                if (change is MediaItemChange.Updated) {
                    _state.update { state ->
                        state.dataOrNull?.let { state.map { items -> items.updatedBy(change) } } ?: state
                    }
                }
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.update { DataState.Loading() }
            val result = mediaItemRepository.fetchMediaItems(Request.Browse.atPath(path))
            result.getOrNull()
                ?.let { items -> _state.update { DataState.Data(items) } }
                ?: run {
                    Logger.e("Error browsing path=$path:", result.exceptionOrNull())
                    _state.update { DataState.Error() }
                }
        }
    }

    // Mirrors ItemListViewModel.onPlayClick — play a browsed item on the selected player.
    fun onPlayClick(
        item: AppMediaItem,
        option: QueueOption,
        radio: Boolean,
    ) {
        val queueId = mainDataSource.selectedPlayer?.queueOrPlayerId ?: return
        item.mediaUri?.let { mediaUri ->
            Logger.withTag("PlayDispatch")
                .i { "BrowseViewModel: uri=$mediaUri option=$option radio=$radio queue=$queueId" }
            playDispatcher.play(
                PlayRequest(
                    queueOrPlayerId = queueId,
                    media = listOf(mediaUri),
                    option = option,
                    endlessMixMode = radio && item !is Genre,
                ),
            )
        }
    }
}

/** The browsed [this] items, each one [change] is about replaced by its new version. */
internal fun List<AppMediaItem>.updatedBy(change: MediaItemChange.Updated): List<AppMediaItem> =
    map { item ->
        if (item.isSameItemAs(change.item)) change.item.withFavorite(change.favoriteAfter(item.favorite)) else item
    }

/**
 * Whether this browsed item is what [current] plays: the item itself, or the library item it was
 * added as. The web app marks the playing item of a browse list the same way.
 */
internal fun AppMediaItem.isPlayedAs(current: AppMediaItem?): Boolean =
    current?.let { isSameItemAs(it) || hasAnyMappingFrom(it) } == true

private fun AppMediaItem.isSameItemAs(other: AppMediaItem): Boolean =
    mediaType == other.mediaType && provider == other.provider && itemId == other.itemId
