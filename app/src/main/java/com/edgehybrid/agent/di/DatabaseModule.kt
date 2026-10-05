package com.edgehybrid.agent.di

import android.content.Context
import com.edgehybrid.agent.data.local.ChatDao
import com.edgehybrid.agent.data.local.ChatDatabase
import com.edgehybrid.agent.data.local.LessonsDao
import com.edgehybrid.agent.data.local.NoteDao
import com.edgehybrid.agent.data.local.VectorDao
import com.edgehybrid.agent.memory.UserMemoryDao
import com.edgehybrid.agent.skills.ProcedureSkillSource
import com.edgehybrid.agent.skills.UserSkillStore
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

    @Provides
    @Singleton
    fun provideUserMemoryDao(database: ChatDatabase): UserMemoryDao {
        return database.userMemoryDao()
    }

    /**
     * Bound to the interface, not the class, so prompt assembly depends on the
     * capability rather than on file storage. That is what lets
     * `AgentContextProviderTest` verify the assembled prompt on the JVM.
     */
    @Provides
    @Singleton
    fun provideProcedureSkillSource(store: UserSkillStore): ProcedureSkillSource = store
}
