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

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Share, contentDescription = "Chat") },
                    label = { Text("Chat") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Build, contentDescription = "Skills") },
                    label = { Text("Skills") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Check, contentDescription = "Settings") },
                    label = { Text("Settings") }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> ChatRoute(onOpenSettings = { selectedTab = 2 })
                1 -> SkillsScreen(
                    skillLoader = skillLoader,
                    mcpClient = mcpClient,
                    onNavigateBack = { selectedTab = 0 }
                )
                2 -> SettingsScreen(
                    keyStore = keyStore,
                    lessonsDao = lessonsDao,
                    onNavigateBack = { selectedTab = 0 }
                )
            }
        }
    }
}