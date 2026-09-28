package com.edgehybrid.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.edgehybrid.agent.core.mcp.McpClient
import com.edgehybrid.agent.core.tools.SkillLoader
import com.edgehybrid.agent.data.local.LessonsDao
import com.edgehybrid.agent.data.local.SecureKeyStore
import com.edgehybrid.agent.ui.chat.ChatScreen
import com.edgehybrid.agent.ui.chat.ChatViewModel
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
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()

                    NavHost(
                        navController = navController,
                        startDestination = "chat"
                    ) {
                        composable("chat") {
                            val viewModel: ChatViewModel = hiltViewModel()
                            ChatScreen(
                                viewModel = viewModel,
                                onNavigateToSettings = { navController.navigate("settings") },
                                onNavigateToSkills = { navController.navigate("skills") }
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                keyStore = keyStore,
                                lessonsDao = lessonsDao,
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                        composable("skills") {
                            SkillsScreen(
                                skillLoader = skillLoader,
                                mcpClient = mcpClient,
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}
