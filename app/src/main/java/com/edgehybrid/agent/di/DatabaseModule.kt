package com.edgehybrid.agent.di

import android.content.Context
import com.edgehybrid.agent.data.local.ChatDao
import com.edgehybrid.agent.data.local.ChatDatabase
import com.edgehybrid.agent.data.local.LessonsDao
import com.edgehybrid.agent.data.local.NoteDao
import com.edgehybrid.agent.data.local.VectorDao
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
    fun provideChatDatabase(@ApplicationContext context: Context): ChatDatabase {
        return ChatDatabase.getInstance(context)
    }

    @Provides
    fun provideChatDao(database: ChatDatabase): ChatDao {
        return database.chatDao()
    }

    @Provides
    fun provideLessonsDao(database: ChatDatabase): LessonsDao {
        return database.lessonsDao()
    }

    @Provides
    fun provideNoteDao(database: ChatDatabase): NoteDao {
        return database.noteDao()
    }

    @Provides
    fun provideVectorDao(database: ChatDatabase): VectorDao {
        return database.vectorDao()
    }
}
