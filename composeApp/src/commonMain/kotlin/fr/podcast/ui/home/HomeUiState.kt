package fr.podcast.ui.home

import fr.podcast.shared.home.HomeState
import fr.podcast.shared.home.LoadHomeState
import fr.podcast.shared.model.Podcast
import fr.podcast.shared.repository.PodcastRepository
import kotlinx.coroutines.CancellationException

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Loaded(val home: HomeState, val subscriptions: List<Podcast>) : HomeUiState
    data class Failed(val error: Exception) : HomeUiState
}

suspend fun loadHome(repository: PodcastRepository): HomeUiState =
    try {
        HomeUiState.Loaded(LoadHomeState(repository)(), repository.subscriptions())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        HomeUiState.Failed(failure)
    }
