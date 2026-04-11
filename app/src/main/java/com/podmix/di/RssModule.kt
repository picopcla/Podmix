package com.podmix.di

import com.prof18.rssparser.RssParser
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RssModule {

    @Provides
    @Singleton
    fun provideRssParser(): RssParser = RssParser()
}
