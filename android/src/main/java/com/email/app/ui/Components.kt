package com.email.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.email.api.Address
import com.email.api.FolderRole
import com.email.app.Container
import com.email.app.container
import com.email.app.ui.theme.LocalBackdrop
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** The icon's aurora, violet into green. */
val BrandGradient = Brush.linearGradient(listOf(Color(0xFF5B3FD1), Color(0xFF0F9D77)))

/** The app's container, from inside the screens. */
@Composable
fun appContainer(): Container = LocalContext.current.container

/** Everything behind the screens: the theme's gentle gradient, as in the other apps. */
@Composable
fun Backdrop(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = LocalBackdrop.current
    Box(
        modifier.fillMaxSize().drawBehind {
            drawRect(colors.base)
            // Two soft glows of the theme at the top, like an aurora.
            drawRect(Brush.radialGradient(listOf(colors.glowA, Color.Transparent), center = Offset(size.width * 0.1f, -size.height * 0.04f), radius = size.width * 1.05f))
            drawRect(Brush.radialGradient(listOf(colors.glowB, Color.Transparent), center = Offset(size.width * 0.95f, size.height * 0.02f), radius = size.width * 0.85f))
        },
    ) { content() }
}

/** A screen's title, large and left-aligned over the backdrop, with its actions on the right. */
@Composable
fun ScreenTitle(title: String, subtitle: String? = null, leading: @Composable (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = if (leading != null) 4.dp else 20.dp, end = 8.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        leading?.invoke()
        Column(Modifier.weight(1f).padding(start = if (leading != null) 4.dp else 0.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        actions()
    }
}

/** A person's circle: their first letter on a steady colour of their own. */
@Composable
fun Avatar(address: Address?, modifier: Modifier = Modifier, size: Dp = 42.dp) {
    val seed = address?.email ?: "?"
    Box(modifier.size(size).clip(CircleShape).background(avatarColour(seed)), contentAlignment = Alignment.Center) {
        Text(initial(address), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp, textAlign = TextAlign.Center)
    }
}

/** An empty screen: a round icon, a title and a line. */
@Composable
fun EmptyState(icon: @Composable () -> Unit, title: String, text: String?, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onPrimaryContainer) { icon() }
        }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        text?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
        action?.invoke()
    }
}

private val AVATAR_COLOURS = listOf(0xFF6A3FD6, 0xFF0F9D77, 0xFF1D5FC9, 0xFFB4471A, 0xFF8E3F5C, 0xFF0E8FA3, 0xFF7C5B12, 0xFF4F6B1F, 0xFFA3416A, 0xFF3D57B3)

/** The same colours for the same people as on the web app. */
fun avatarColour(seed: String): Color {
    var hash = 0
    seed.forEach { hash = hash * 31 + it.code }
    return Color(AVATAR_COLOURS[Math.floorMod(hash, AVATAR_COLOURS.size)])
}

fun initial(address: Address?): String =
    (address?.shown ?: "").firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"

/** A message's date in a list: the time today, the weekday this week, the day this year, else the date. */
fun shortDate(ms: Long, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String {
    if (ms <= 0) return ""
    val time = Instant.ofEpochMilli(ms).atZone(zone)
    val date = time.toLocalDate()
    return when {
        date == today -> time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
        date.isAfter(today.minusDays(6)) && !date.isAfter(today) -> time.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault()))
        date.year == today.year -> time.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
        else -> time.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }
}

fun fullDate(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    if (ms <= 0) "" else Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))

fun fileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
}

/** What folders are called, whatever the server names them. */
fun folderName(role: FolderRole, name: String): String = when (role) {
    FolderRole.INBOX -> "Inbox"
    FolderRole.DRAFTS -> "Drafts"
    FolderRole.SENT -> "Sent"
    FolderRole.ARCHIVE -> "Archive"
    FolderRole.JUNK -> "Junk"
    FolderRole.TRASH -> "Trash"
    FolderRole.ALL -> "All mail"
    FolderRole.FLAGGED -> "Starred"
    FolderRole.OTHER -> name
}

/** "Name <a@b>", or "a@b", typed into a recipient field. */
fun parseAddress(text: String): Address? {
    val t = text.trim().trimEnd(',', ';')
    Regex("^(.*)<\\s*([^<>\\s]+@[^<>\\s]+)\\s*>$").find(t)?.let { m ->
        return Address(m.groupValues[2], m.groupValues[1].trim().trim('"').ifBlank { null })
    }
    return if (Regex("^[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+$").matches(t)) Address(t) else null
}

/** Opens a web address in the browser. */
fun openInBrowser(context: Context, url: String) {
    runCatching {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri()).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
