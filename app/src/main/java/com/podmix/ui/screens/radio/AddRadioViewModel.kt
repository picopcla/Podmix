package com.podmix.ui.screens.radio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podmix.data.api.RadioBrowserApi
import com.podmix.data.api.RadioStation
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
class AddRadioViewModel @Inject constructor(
    private val radioBrowserApi: RadioBrowserApi,
    private val repository: PodcastRepository
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _results = MutableStateFlow<List<RadioStation>>(emptyList())
    val results: StateFlow<List<RadioStation>> = _results

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _addedEvent = MutableSharedFlow<Unit>()
    val addedEvent = _addedEvent

    private val searchTrigger = MutableSharedFlow<String>(extraBufferCapacity = 1)

    init {
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            searchTrigger
                .debounce(400)
                .filter { it.length >= 2 }
                .collect { term ->
                    _isLoading.value = true
                    try {
                        _results.value = radioBrowserApi.searchByName(term)
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

    fun addRadio(station: RadioStation) {
        val streamUrl = station.urlResolved ?: station.url
        viewModelScope.launch {
            repository.addRadio(
                name = station.name.trim(),
                streamUrl = streamUrl,
                logoUrl = station.favicon?.takeIf { it.isNotBlank() }
            )
            _addedEvent.emit(Unit)
        }
    }
}
