package org.asimon.sefaria.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import org.asimon.sefaria.BookRoute
import org.asimon.sefaria.viewmodels.BookViewModel


@Composable
fun BookScreen(
    bookRoute: BookRoute,
    viewModel: BookViewModel = hiltViewModel(),
) {
    val text = viewModel.textPager.collectAsLazyPagingItems()

    Column {
        Text(
            "Book ${bookRoute.ref}",
            fontSize = 20.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        LazyColumn(modifier = Modifier.fillMaxHeight()) {
            when (val refresh = text.loadState.refresh) {
                is LoadState.Loading -> item { CircularProgressIndicator() }
                is LoadState.Error -> item { Text(refresh.error.message ?: "") }
                else -> {}
            }
            items(text.itemCount, text.itemKey { it.ref }) { index ->
                val line = text[index]
                line?.let {
                    Text(AnnotatedString.fromHtml(line.text), fontSize = 20.sp)
                }
            }
            when (val append = text.loadState.append) {
                is LoadState.Loading -> item { CircularProgressIndicator() }
                is LoadState.Error -> item { Text(append.error.message ?: "") }
                else -> {}
            }
        }
    }
}