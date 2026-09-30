package com.edgehybrid.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.edgehybrid.agent.core.mcp.McpClient
import com.edgehybrid.agent.core.tools.SkillLoader
import com.edgehybrid.agent.data.local.LessonsDao
import com.edgehybrid.agent.data.local.SecureKeyStore
import com.edgehybrid.agent.ui.chat.ChatRoute
import com.edgehybrid.agent.ui.settings.SettingsScreen
import com.edgehybrid.agent.ui.skills.SkillsScreen
import com.edgehybrid.agent.ui.theme.EdgeHybridTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

import com.edgehybrid.agent.ui.canvas.CanvasInspectorScreen
import com.edgehybrid.agent.ui.components.AuraBottomNavigationBar
import com.edgehybrid.agent.ui.components.AuraHeader
import com.edgehybrid.agent.ui.library.LibraryMemoryScreen
import com.edgehybrid.agent.ui.theme.AuraTokens

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var keyStore: SecureKeyStore

    @Inject
    lateinit var lessonsDao: LessonsDao

    @Inject
    lateinit var skillLoader: SkillLoader

    @Inject
    lateinit var mcpClient: McpClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EdgeHybridTheme {
                MainAppScreen(
                    keyStore = keyStore,
                    lessonsDao = lessonsDao,
                    skillLoader = skillLoader,
                    mcpClient = mcpClient
                )
            }
        }
    }
}

@Composable
fun MainAppScreen(
    keyStore: SecureKeyStore,
    lessonsDao: LessonsDao,
    skillLoader: SkillLoader,
    mcpClient: McpClient
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    val tabTitle = when (selectedTab) {
        0 -> "Chats"
        1 -> "Canvas"
        2 -> "Library"
        else -> "Settings"
    }

    Scaffold(
        topBar = {
            AuraHeader(
                title = tabTitle,
                onProfileClick = { selectedTab = 3 }
            )
        },
        bottomBar = {
            AuraBottomNavigationBar(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it }
            )
        },
        containerColor = AuraTokens.Surface
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> ChatRoute(onOpenSettings = { selectedTab = 3 })
                1 -> CanvasInspectorScreen(onNavigateBack = { selectedTab = 0 })
                2 -> LibraryMemoryScreen(
                    lessonsDao = lessonsDao,
                    onNavigateBack = { selectedTab = 0 }
                )
                3 -> SettingsScreen(
                    keyStore = keyStore,
                    lessonsDao = lessonsDao,
                    onNavigateBack = { selectedTab = 0 }
                )
            }
        }
    }
}