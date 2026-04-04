package com.nostr.unfiltered.di

import android.content.Context
import com.nostr.unfiltered.nostr.KeyManager
import com.nostr.unfiltered.nostr.NostrClient
import com.nostr.unfiltered.nostr.ZapVelocityService
import com.nostr.unfiltered.repository.ZapDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideKeyManager(
        @ApplicationContext context: Context
    ): KeyManager {
        return KeyManager(context)
    }

    @Provides
    @Singleton
    fun provideNostrClient(): NostrClient {
        return NostrClient()
    }

    @Provides
    @Singleton
    fun provideZapDatabase(
        @ApplicationContext context: Context
    ): ZapDatabase {
        return ZapDatabase.getDatabase(context)
    }

    @Provides
    @Singleton
    fun provideZapVelocityService(
        nostrClient: NostrClient,
        database: ZapDatabase
    ): ZapVelocityService {
        return ZapVelocityService(nostrClient, database)
    }
}
