package com.podmix.v2.di

import android.content.Context
import androidx.room.Room
import com.podmix.v2.core.database.PodmixV2Database
import com.podmix.v2.data.local.dao.BackendHealthDao
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
    fun provideDatabase(@ApplicationContext context: Context): PodmixV2Database =
        Room.databaseBuilder(
            context,
            PodmixV2Database::class.java,
            "podmix-v2.db"
        ).build()

    @Provides
    fun provideBackendHealthDao(database: PodmixV2Database): BackendHealthDao = database.backendHealthDao()
}
