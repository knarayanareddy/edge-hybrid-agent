package com.edgehybrid.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.Scaffold
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

import com.edgehybrid.agent.ui.components.AuraBottomNavigationBar
import com.edgehybrid.agent.ui.components.AuraHeader
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

/** Tab indices */
private const val TAB_CHAT     = 0
private const val TAB_SKILLS   = 1
private const val TAB_SETTINGS = 2

@Composable
fun MainAppScreen(
    keyStore: SecureKeyStore,
    lessonsDao: LessonsDao,
    skillLoader: SkillLoader,
    mcpClient: McpClient
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_CHAT) }

    val tabTitle = when (selectedTab) {
        TAB_CHAT     -> "Chat"
        TAB_SKILLS   -> "Skills & Tools"
        TAB_SETTINGS -> "Settings"
        else         -> "Chat"
    }

    Scaffold(
        topBar = {
            AuraHeader(
                title = tabTitle,
                onProfileClick = { selectedTab = TAB_SETTINGS }
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
                TAB_CHAT -> ChatRoute(onOpenSettings = { selectedTab = TAB_SETTINGS })

                TAB_SKILLS -> SkillsScreen(
                    skillLoader = skillLoader,
                    mcpClient = mcpClient,
                    onNavigateBack = { selectedTab = TAB_CHAT }
                )

                TAB_SETTINGS -> SettingsScreen(
                    keyStore = keyStore,
                    lessonsDao = lessonsDao,
                    onNavigateBack = { selectedTab = TAB_CHAT }
                )
            }
        }
    }
}