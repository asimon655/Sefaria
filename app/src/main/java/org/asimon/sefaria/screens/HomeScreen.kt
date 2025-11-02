package org.asimon.sefaria.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.asimon.sefaria.data.Book
import org.asimon.sefaria.data.Category
import org.asimon.sefaria.data.Content
import org.asimon.sefaria.viewmodels.HomeUiState
import org.asimon.sefaria.viewmodels.HomeViewModel

val LocalNavigateToBook = compositionLocalOf<(ref: String) -> Unit> {
    error("No navigate to book lambda provided")
}

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    navigateToBook: (ref: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    CompositionLocalProvider(LocalNavigateToBook provides navigateToBook) {
        when (state) {
            is HomeUiState.Loading -> {
                CircularProgressIndicator()
            }
            is HomeUiState.Success -> {
                val table = (state as HomeUiState.Success).tableOfContent
                TableOfContent(table, viewModel::isExpanded, viewModel::toggleExpanded)
            }
            is HomeUiState.Error -> {
                Text((state as HomeUiState.Error).message)
            }
        }
    }
}


@Composable
fun Category(category: Category, level: Int, modifier: Modifier) {
    Card(modifier = modifier
        .padding(5.dp)
        .padding(start = (30 * level).dp)
        .fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text("• " + category.nameHe, fontSize = 20.sp)
            if (category.shortDescHe.isNotEmpty())
                Text(category.shortDescHe, fontSize = 10.sp, lineHeight = 10.sp)
        }
    }
}

@Composable
fun Book(book: Book, level: Int) {
    val navigateToBook = LocalNavigateToBook.current
    Card(modifier = Modifier
        .padding(5.dp)
        .padding(start = (30 * level).dp)
        .fillMaxWidth()
        .clickable {
            navigateToBook(book.titleEn)
        }) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text("• " + book.titleHe, fontSize = 20.sp)
            if (book.shortDescHe.isNotEmpty())
                Text(book.shortDescHe, fontSize = 10.sp, lineHeight = 10.sp)
        }
    }
}

fun LazyListScope.nodes(
    contents: List<Content>,
    isExpanded: (String) -> Boolean,
    toggleExpanded: (String) -> Unit,
    level: Int = 0
) {
    contents.forEach { content ->
        node(
            content,
            isExpanded = isExpanded,
            toggleExpanded = toggleExpanded,
            level
        )
    }
}

fun LazyListScope.node(
    content: Content,
    isExpanded: (String) -> Boolean,
    toggleExpanded: (String) -> Unit,
    level: Int
) {
    when (content) {
        is Category -> {
            item {
                Category(content, level,  Modifier.clickable {
                    toggleExpanded(content.nameEn)
                })
            }
            if (isExpanded(content.nameEn)) {
                nodes(
                    content.contents,
                    isExpanded = isExpanded,
                    toggleExpanded = toggleExpanded,
                    level + 1
                )
            }
        }
        is Book -> {
            item {
                Book(content, level)
            }
        }
    }
}


@Composable
fun TableOfContent(table: List<Content>, isExpanded: (String) -> Boolean, toggleExpanded: (String) -> Unit) {

    LazyColumn {
        nodes(
            table,
            isExpanded,
            toggleExpanded
        )
    }
}
