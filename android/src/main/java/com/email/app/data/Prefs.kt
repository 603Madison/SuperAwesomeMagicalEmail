package com.email.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.email.api.ApiJson
import com.email.api.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("email")

/** The app's settings, kept on the phone. */
data class Settings(
    /** The server's address, e.g. https://mail.example.com; empty before signing in. */
    val server: String = "",
    /** The app's sign-in token (long-lived, and revocable from the server's Settings). */
    val token: String? = null,
    val user: User? = null,
    val serverName: String = "",
    /** Tell about new mail while the app is closed. */
    val notifications: Boolean = true,
    /** Show images from the web in messages without asking. */
    val alwaysShowImages: Boolean = false,
    /** Light, dark, or as the phone is. */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** The phone's wallpaper colours (Android 12 and later) instead of the app's own. */
    val wallpaperColors: Boolean = false,
    /** All text in Lexend Bold, as the phone's own Bold text setting draws it. */
    val boldText: Boolean = false,
    /** The colours (unless the wallpaper's are used). */
    val palette: Palette = Palette.AURORA,
) {
    val signedIn: Boolean get() = server.isNotEmpty() && token != null
}

/** Light, dark, or as the phone is. */
enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

/** The themes, as on the web app and the desktop app, with the two colours each shows in Settings. */
enum class Palette(val label: String, val swatchA: Long, val swatchB: Long) {
    AURORA("Aurora", 0xFF6A3FD6, 0xFF12A37C),
    VIOLET("Violet", 0xFF5B3FD1, 0xFF8E3F5C),
    OCEAN("Ocean", 0xFF1D5FC9, 0xFF0E8FA3),
    EMBER("Ember", 0xFFB4471A, 0xFF9C3F60),
}

class Prefs(private val context: Context) {
    val settings: Flow<Settings> = context.store.data.map(::read)

    suspend fun current(): Settings = settings.first()

    suspend fun signIn(server: String, token: String, user: User, serverName: String) = context.store.edit {
        it[SERVER] = server
        it[TOKEN] = token
        it[USER] = ApiJson.encodeToString(User.serializer(), user)
        it[SERVER_NAME] = serverName
        it.remove(NEW_MAIL_CURSOR)
    }

    suspend fun signOut() = context.store.edit {
        it.remove(TOKEN)
        it.remove(USER)
        it.remove(NEW_MAIL_CURSOR)
    }

    suspend fun update(transform: (Settings) -> Settings) = context.store.edit { prefs ->
        val next = transform(read(prefs))
        prefs[NOTIFICATIONS] = next.notifications
        prefs[ALWAYS_IMAGES] = next.alwaysShowImages
        prefs[THEME_MODE] = next.themeMode.name
        prefs[WALLPAPER_COLORS] = next.wallpaperColors
        prefs[BOLD_TEXT] = next.boldText
        prefs[PALETTE] = next.palette.name
        next.user?.let { prefs[USER] = ApiJson.encodeToString(User.serializer(), it) }
    }

    /** Where the last check for new mail got to (see the server's /api/mail/new). */
    suspend fun newMailCursor(): String? = context.store.data.first()[NEW_MAIL_CURSOR]

    suspend fun setNewMailCursor(cursor: String) = context.store.edit { it[NEW_MAIL_CURSOR] = cursor }

    private fun read(prefs: Preferences) = Settings(
        server = prefs[SERVER].orEmpty(),
        token = prefs[TOKEN],
        user = prefs[USER]?.let { runCatching { ApiJson.decodeFromString(User.serializer(), it) }.getOrNull() },
        serverName = prefs[SERVER_NAME].orEmpty(),
        notifications = prefs[NOTIFICATIONS] ?: true,
        alwaysShowImages = prefs[ALWAYS_IMAGES] ?: false,
        themeMode = ThemeMode.entries.firstOrNull { it.name == prefs[THEME_MODE] } ?: ThemeMode.SYSTEM,
        wallpaperColors = prefs[WALLPAPER_COLORS] ?: false,
        boldText = prefs[BOLD_TEXT] ?: false,
        palette = Palette.entries.firstOrNull { it.name == prefs[PALETTE] } ?: Palette.AURORA,
    )

    private companion object {
        val SERVER = stringPreferencesKey("server")
        val TOKEN = stringPreferencesKey("token")
        val USER = stringPreferencesKey("user")
        val SERVER_NAME = stringPreferencesKey("server_name")
        val NOTIFICATIONS = booleanPreferencesKey("notifications")
        val ALWAYS_IMAGES = booleanPreferencesKey("always_images")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val WALLPAPER_COLORS = booleanPreferencesKey("wallpaper_colors")
        val BOLD_TEXT = booleanPreferencesKey("bold_text")
        val PALETTE = stringPreferencesKey("palette")
        val NEW_MAIL_CURSOR = stringPreferencesKey("new_mail_cursor")
    }
}
