package org.asimon.sefaria

import kotlinx.serialization.Serializable

const val HomeRoute = "home"
const val AccountRoute = "account"
const val SearchRoute = "search"
const val SavedRoute = "saved"
@Serializable
data class BookRoute(
    val ref: String,
)

const val DEFAULT_ROUTE = HomeRoute
val MAIN_ROUTES: List<String> = listOf(HomeRoute, AccountRoute, SearchRoute, SavedRoute)