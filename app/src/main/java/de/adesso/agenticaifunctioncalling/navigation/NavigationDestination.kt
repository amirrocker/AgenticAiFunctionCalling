package de.adesso.agenticaifunctioncalling.navigation

sealed class NavigationDestination {
    data object Chat : NavigationDestination()

    data object Deposits : NavigationDestination()

    data class Relocation(val city: String? = null) : NavigationDestination()

    data object Contracts : NavigationDestination()
}