package org.asimon.sefaria.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun ErrorMessage(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
        if (onRetry != null) Button(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
}

/** An English title with its Hebrew title underneath (either may be empty). */
@Composable
fun BilingualTitle(en: String, he: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        if (en.isNotEmpty()) Text(en, style = MaterialTheme.typography.titleMedium)
        if (he.isNotEmpty()) Text(
            he,
            style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Rtl),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
