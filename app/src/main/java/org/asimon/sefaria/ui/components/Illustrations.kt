package org.asimon.sefaria.ui.components

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.asimon.sefaria.R
import org.asimon.sefaria.data.AppError

/** The pictures of the empty and error states, drawn in the theme's colours (a 200×160 canvas, like an SVG). */
enum class Art { BrokenLink, Offline, Error, NoResults }

private fun art(build: ImageVector.Builder.() -> Unit) =
    ImageVector.Builder(defaultWidth = 200.dp, defaultHeight = 160.dp, viewportWidth = 200f, viewportHeight = 160f).apply(build).build()

private fun ImageVector.Builder.fill(d: String, c: Color) = addPath(addPathNodes(d), fill = SolidColor(c))

private fun ImageVector.Builder.stroke(d: String, c: Color, w: Float, fill: Color? = null) = addPath(
    addPathNodes(d), fill = fill?.let(::SolidColor), stroke = SolidColor(c), strokeLineWidth = w,
    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
)

/** A soft disc behind each picture. */
private const val DISC = "M34,80 A66,66 0 1,0 166,80 A66,66 0 1,0 34,80 Z"

private fun build(a: Art, c: ColorScheme): ImageVector = when (a) {
    // two halves of a chain link, pulled apart, with sparks where they broke
    Art.BrokenLink -> art {
        fill(DISC, c.primaryContainer)
        addGroup(rotate = -25f, pivotX = 100f, pivotY = 80f)
        stroke("M90,60 H58 A20,20 0 0,0 58,100 H90", c.primary, 9f)
        stroke("M68,80 H84", c.primary, 9f)
        stroke("M110,60 H142 A20,20 0 0,1 142,100 H110", c.primary, 9f)
        stroke("M116,80 H132", c.primary, 9f)
        clearGroup()
        for (d in listOf("M100,44 V32", "M87,48 L79,38", "M113,48 L121,38", "M100,116 V128", "M87,112 L79,122", "M113,112 L121,122")) {
            stroke(d, c.secondary, 5f)
        }
    }
    // a cloud, crossed out
    Art.Offline -> art {
        fill(DISC, c.primaryContainer)
        stroke("M60,112 H146 A22,22 0 0,0 146,68 A36,36 0 0,0 78,62 A26,26 0 0,0 60,112 Z", c.outline, 4f, fill = c.surface)
        stroke("M86,92 A16,16 0 0,1 118,92", c.outlineVariant, 5f)
        stroke("M56,38 L150,128", c.primaryContainer, 16f)
        stroke("M56,38 L150,128", c.error, 7f)
    }
    // a page with a warning sign on its corner
    Art.Error -> art {
        fill(DISC, c.primaryContainer)
        stroke("M66,30 H120 L138,48 V128 A6,6 0 0,1 132,134 H66 A6,6 0 0,1 60,128 V36 A6,6 0 0,1 66,30 Z", c.outline, 4f, fill = c.surface)
        stroke("M120,30 V48 H138", c.outline, 4f)
        stroke("M74,62 H118", c.outlineVariant, 5f)
        stroke("M74,76 H124", c.outlineVariant, 5f)
        stroke("M74,90 H106", c.outlineVariant, 5f)
        stroke("M144,90 L170,136 H118 Z", c.secondary, 6f, fill = c.secondary)
        stroke("M144,106 V120", c.onSecondary, 6f)
        stroke("M144,129 V129.5", c.onSecondary, 6f)
    }
    // a magnifier over an empty line
    Art.NoResults -> art {
        fill(DISC, c.primaryContainer)
        stroke("M92,48 A32,32 0 1,0 92,112 A32,32 0 1,0 92,48 Z", c.primary, 9f, fill = c.surface)
        stroke("M76,82 Q84,74 92,82 T108,82", c.secondary, 5f)
        stroke("M116,104 L142,130", c.primary, 13f)
    }
}

@Composable
fun rememberArt(a: Art): ImageVector {
    val c = MaterialTheme.colorScheme
    return remember(a, c) { build(a, c) }
}

/**
 * A picture, a title, a line of explanation and an optional action, centred: the empty and
 * error states. [compact] for a panel or a list (a smaller picture, less padding).
 */
@Composable
fun EmptyState(
    art: Art,
    title: String,
    body: String? = null,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = if (compact) 12.dp else 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
    ) {
        Image(rememberArt(art), contentDescription = null, Modifier.size(if (compact) 120.dp else 200.dp, if (compact) 96.dp else 160.dp))
        Text(title, style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body != null) Text(
            body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        if (action != null) action()
    }
}

/** Whether the device has a network with internet access (to tell "no internet" from "the server is down"). */
fun isOnline(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/**
 * A failed load as a page: no internet, the server unreachable, or another error (with the
 * error's own text), and a retry button.
 */
@Composable
fun ErrorMessage(error: AppError, onRetry: (() -> Unit)?, modifier: Modifier = Modifier, compact: Boolean = false) {
    val context = LocalContext.current
    val (art, title, body) = when {
        error == AppError.Network && !isOnline(context) ->
            Triple(Art.Offline, stringResource(R.string.offline_title), stringResource(R.string.offline_body))
        error == AppError.Network ->
            Triple(Art.Offline, stringResource(R.string.server_down_title), stringResource(R.string.server_down_body))
        error == AppError.PrefixOffline ->
            Triple(Art.Offline, stringResource(R.string.prefix_offline_title), error.text())
        else -> Triple(Art.Error, stringResource(R.string.error_title), error.text())
    }
    EmptyState(art, title, body, modifier, compact) {
        if (onRetry != null) Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}
