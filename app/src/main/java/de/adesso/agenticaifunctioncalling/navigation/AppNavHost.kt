package de.adesso.agenticaifunctioncalling.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.adesso.agenticaifunctioncalling.ui.agent.AgentScreen
import de.adesso.agenticaifunctioncalling.ui.agent.AgentViewModel
import de.adesso.agenticaifunctioncalling.ui.agent.ContractsScreen
import de.adesso.agenticaifunctioncalling.ui.agent.DepositsScreen
import de.adesso.agenticaifunctioncalling.ui.agent.RelocationScreen
import org.koin.androidx.compose.koinViewModel

@Composable
fun AppNavHost(
    viewModel: AgentViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    AnimatedContent(
        targetState = uiState.currentDestination,
        transitionSpec = {
            (slideInHorizontally { it } + fadeIn()) togetherWith
                    (slideOutHorizontally { -it } + fadeOut())
        },
        label = "app_nav_host"
    ) { destination ->
        when (destination) {

            is NavigationDestination.Chat ->
                AgentScreen()

            is NavigationDestination.Deposits ->
                DepositsScreen(
                    onBack = { viewModel.navigateTo(NavigationDestination.Chat) }
                )

            is NavigationDestination.Relocation ->
                RelocationScreen(
                    city = destination.city,
                    onBack = { viewModel.navigateTo(NavigationDestination.Chat) }
                )

            is NavigationDestination.Contracts ->
                ContractsScreen(
                    onBack = { viewModel.navigateTo(NavigationDestination.Chat) }
                )
        }
    }
}