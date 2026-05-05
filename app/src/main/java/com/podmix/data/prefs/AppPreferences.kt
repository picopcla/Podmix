package com.podmix.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "podmix_prefs")

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        val KEY_MAX_PODCAST_EPISODES  = intPreferencesKey("max_podcast_episodes")
        val KEY_MAX_LIVESET_EPISODES  = intPreferencesKey("max_liveset_episodes")
        val KEY_MAX_EMISSION_EPISODES = intPreferencesKey("max_emission_episodes")

        const val DEFAULT_MAX_PODCAST  = 20
        const val DEFAULT_MAX_LIVESET  = 50
        const val DEFAULT_MAX_EMISSION = 10
    }

    val maxPodcastEpisodes: Flow<Int> = context.dataStore.data
        .map { it[KEY_MAX_PODCAST_EPISODES] ?: DEFAULT_MAX_PODCAST }

    val maxLivesetEpisodes: Flow<Int> = context.dataStore.data
        .map { it[KEY_MAX_LIVESET_EPISODES] ?: DEFAULT_MAX_LIVESET }

    val maxEmissionEpisodes: Flow<Int> = context.dataStore.data
        .map { it[KEY_MAX_EMISSION_EPISODES] ?: DEFAULT_MAX_EMISSION }

    suspend fun setMaxPodcastEpisodes(value: Int) {
        context.dataStore.edit { it[KEY_MAX_PODCAST_EPISODES] = value.coerceIn(5, 200) }
    }

    suspend fun setMaxLivesetEpisodes(value: Int) {
        context.dataStore.edit { it[KEY_MAX_LIVESET_EPISODES] = value.coerceIn(5, 500) }
    }

    suspend fun setMaxEmissionEpisodes(value: Int) {
        context.dataStore.edit { it[KEY_MAX_EMISSION_EPISODES] = value.coerceIn(5, 100) }
    }
}
