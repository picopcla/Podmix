package com.podmix.v2.di

import com.podmix.v2.data.repository.BackendHealthRepositoryImpl
import com.podmix.v2.domain.repository.BackendHealthRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindBackendHealthRepository(
        impl: BackendHealthRepositoryImpl
    ): BackendHealthRepository
}
