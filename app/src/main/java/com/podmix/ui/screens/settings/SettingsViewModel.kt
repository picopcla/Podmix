package com.podmix.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podmix.data.prefs.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: AppPreferences
) : ViewModel() {

    val maxPodcastEpisodes = prefs.maxPodcastEpisodes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferences.DEFAULT_MAX_PODCAST)

    val maxLivesetEpisodes = prefs.maxLivesetEpisodes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferences.DEFAULT_MAX_LIVESET)

    val maxEmissionEpisodes = prefs.maxEmissionEpisodes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferences.DEFAULT_MAX_EMISSION)

    fun setMaxPodcast(v: Int)  { viewModelScope.launch { prefs.setMaxPodcastEpisodes(v) } }
    fun setMaxLiveset(v: Int)  { viewModelScope.launch { prefs.setMaxLivesetEpisodes(v) } }
    fun setMaxEmission(v: Int) { viewModelScope.launch { prefs.setMaxEmissionEpisodes(v) } }
}
