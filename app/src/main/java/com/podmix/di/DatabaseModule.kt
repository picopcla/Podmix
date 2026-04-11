package com.podmix.di

import android.content.Context
import androidx.room.Room
import com.podmix.data.local.PodMixDatabase
import com.podmix.data.local.dao.EpisodeDao
import com.podmix.data.local.dao.PodcastDao
import com.podmix.data.local.dao.TrackDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PodMixDatabase =
        Room.databaseBuilder(context, PodMixDatabase::class.java, "podmix.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun providePodcastDao(db: PodMixDatabase): PodcastDao = db.podcastDao()
    @Provides fun provideEpisodeDao(db: PodMixDatabase): EpisodeDao = db.episodeDao()
    @Provides fun provideTrackDao(db: PodMixDatabase): TrackDao = db.trackDao()
}
