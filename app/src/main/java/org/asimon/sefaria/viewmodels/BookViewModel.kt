package org.asimon.sefaria.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import org.asimon.sefaria.data.paginsource.TextPagingSource
import org.asimon.sefaria.data.repositories.DataRepository
import javax.inject.Inject


@HiltViewModel
class BookViewModel @Inject constructor(
    private val dataRepository: DataRepository,
    private val savedStateHandle: SavedStateHandle,
): ViewModel() {
    private val bookRef = checkNotNull(savedStateHandle.get<String>("ref")) { "got no ref param in BookViewModel" }
    val textPager = Pager(
        PagingConfig(pageSize = 1)
    ) {
        TextPagingSource(dataRepository, bookRef)
    }.flow
//        .map { pagingData ->
//        pagingData.insertSeparators { before, after ->
//            when {
//                before != null -> null // is before last ref
//                after != null -> null // is after first ref
//                else -> null
//            }
//        }
//    }
        .cachedIn(scope = viewModelScope)
}