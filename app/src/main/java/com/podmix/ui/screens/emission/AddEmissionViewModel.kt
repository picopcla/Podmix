package com.podmix.ui.screens.emission

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podmix.data.api.ItunesApi
import com.podmix.data.api.ItunesPodcast
import com.podmix.data.repository.PodcastRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AddEmissionViewModel @Inject constructor(
    private val itunesApi: ItunesApi,
    private val repository: PodcastRepository
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _results = MutableStateFlow<List<ItunesPodcast>>(emptyList())
    val results: StateFlow<List<ItunesPodcast>> = _results

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _addedEvent = MutableSharedFlow<Unit>()
    val addedEvent = _addedEvent

    private val searchTrigger = MutableSharedFlow<String>(extraBufferCapacity = 1)

    init {
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            searchTrigger
                .debounce(300)
                .filter { it.length >= 2 }
                .collect { term ->
                    _isLoading.value = true
                    try {
                        val response = itunesApi.searchPodcasts(term)
                        _results.value = response.results
                    } catch (_: Exception) {
                        _results.value = emptyList()
                    }
                    _isLoading.value = false
                }
        }
    }

    fun onQueryChange(newQuery: String) {
        _query.value = newQuery
        if (newQuery.length >= 2) {
            searchTrigger.tryEmit(newQuery)
        } else {
            _results.value = emptyList()
        }
    }

    fun addEmission(podcast: ItunesPodcast) {
        val feedUrl = podcast.feedUrl ?: return
        viewModelScope.launch {
            val id = repository.addEmission(
                name = podcast.name,
                feedUrl = feedUrl,
                logoUrl = podcast.artworkUrl
            )
            try {
                repository.refreshPodcast(id)
            } catch (_: Exception) { }
            _addedEvent.emit(Unit)
        }
    }
}
