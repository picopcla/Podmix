package com.podmix.di

import com.podmix.data.api.DuckDuckGoApi
import com.podmix.data.api.ItunesApi
import com.podmix.data.api.MixcloudApi
import com.podmix.data.api.PipedApi
import com.podmix.data.api.RadioBrowserApi
import com.podmix.data.api.TracklistApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(com.podmix.service.PodmixLoggingInterceptor())  // log fichier structuré
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .build()

    @Provides
    @Singleton
    fun provideItunesApi(client: OkHttpClient): ItunesApi =
        Retrofit.Builder()
            .baseUrl("https://itunes.apple.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ItunesApi::class.java)

    @Provides
    @Singleton
    fun providePipedApi(client: OkHttpClient): PipedApi =
        Retrofit.Builder()
            .baseUrl("https://pipedapi.kavin.rocks/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PipedApi::class.java)

    @Provides
    @Singleton
    fun provideTracklistApi(): TracklistApi {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(600, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl("http://192.168.10.5:8099/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TracklistApi::class.java)
    }

    @Provides
    @Singleton
    fun provideMixcloudApi(client: OkHttpClient): MixcloudApi =
        Retrofit.Builder()
            .baseUrl("https://api.mixcloud.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MixcloudApi::class.java)

    @Provides
    @Singleton
    fun provideRadioBrowserApi(client: OkHttpClient): RadioBrowserApi =
        Retrofit.Builder()
            .baseUrl("https://de1.api.radio-browser.info/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(RadioBrowserApi::class.java)

    @Provides
    @Singleton
    fun provideDuckDuckGoApi(client: OkHttpClient): DuckDuckGoApi =
        Retrofit.Builder()
            .baseUrl("https://api.duckduckgo.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(DuckDuckGoApi::class.java)
    
    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun provideAudioAnalysisService(): com.podmix.service.AudioAnalysisService =
        com.podmix.service.AudioAnalysisService()
    
    @Provides
    @Singleton
    fun provideYouTubeCommentsService(
        youTubeStreamResolver: com.podmix.service.YouTubeStreamResolver,
        tracklistService: com.podmix.service.TracklistService,
        geminiParser: com.podmix.service.GeminiTracklistParser
    ): com.podmix.service.YouTubeCommentsService =
        com.podmix.service.YouTubeCommentsService(youTubeStreamResolver, tracklistService, geminiParser)
    
    @Provides
    @Singleton
    fun provideEnhancedTracklistService(
        tracklistService: com.podmix.service.TracklistService,
        youTubeCommentsService: com.podmix.service.YouTubeCommentsService,
        audioAnalysisService: com.podmix.service.AudioAnalysisService
    ): com.podmix.service.EnhancedTracklistService =
        com.podmix.service.EnhancedTracklistService(tracklistService, youTubeCommentsService, audioAnalysisService)
}
