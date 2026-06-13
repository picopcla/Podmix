package com.podmix.v2.features.hub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podmix.v2.domain.usecase.health.ObserveBackendHealthUseCase
import com.podmix.v2.domain.usecase.health.RefreshBackendHealthUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class HubViewModel @Inject constructor(
    observeBackendHealth: ObserveBackendHealthUseCase,
    private val refreshBackendHealth: RefreshBackendHealthUseCase
) : ViewModel() {

    val uiState: StateFlow<HubUiState> = observeBackendHealth()
        .map { HubUiState(backendHealth = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HubUiState())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            refreshBackendHealth()
        }
    }
}
