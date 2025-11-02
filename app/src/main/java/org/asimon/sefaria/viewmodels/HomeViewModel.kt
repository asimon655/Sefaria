package org.asimon.sefaria.viewmodels

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.asimon.sefaria.data.Content
import org.asimon.sefaria.data.repositories.DataRepository
import javax.inject.Inject

sealed interface HomeUiState {
    data object Loading: HomeUiState
    data class Success(
        val tableOfContent: List<Content>
    ): HomeUiState
    data class Error(
        val message: String
    ): HomeUiState
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val dataRepository: DataRepository
): ViewModel() {
    val state: StateFlow<HomeUiState> = dataRepository.getTableOfContent()
        .map {it -> HomeUiState.Success(it) as HomeUiState }
        .catch { e -> emit(HomeUiState.Error(e.message ?: "Unknown error")) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HomeUiState.Loading,
        )
    private val _expandedItems = mutableStateSetOf<String>()

    fun toggleExpanded(ref: String) {
        if (_expandedItems.contains(ref)) {
            _expandedItems.remove(ref)
        } else {
            _expandedItems.add(ref)
        }
    }

    fun isExpanded(ref: String): Boolean {
        return _expandedItems.contains(ref)
    }
}