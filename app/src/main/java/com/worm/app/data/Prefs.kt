package com.worm.app.data

import android.content.Context
import androidx.core.content.edit
import com.worm.app.ui.theme.AppTheme

class Prefs(private val context: Context) {
    private val sp = context.getSharedPreferences("worm_prefs", Context.MODE_PRIVATE)

    private inline fun <reified E : Enum<E>> enumPref(key: String, default: E): E =
        runCatching { enumValueOf<E>(sp.getString(key, null)!!) }.getOrDefault(default)

    // ------------------------------------------------------------- library
    var appTheme: AppTheme
        // The old "Burrow" theme was retired; anyone on it lands on Candy.
        get() = enumPref("app_theme", AppTheme.Candy)
        set(v) = sp.edit { putString("app_theme", v.name) }

    var viewMode: ViewMode
        get() = enumPref("view_mode", ViewMode.Grid3)
        set(v) = sp.edit { putString("view_mode", v.name) }

    var sortKey: SortKey
        get() = enumPref("sort_key", SortKey.Recent)
        set(v) = sp.edit { putString("sort_key", v.name) }

    var sortDescending: Boolean
        get() = sp.getBoolean("sort_desc", false)
        set(v) = sp.edit { putBoolean("sort_desc", v) }

    var kindFilter: KindFilter
        get() = enumPref("kind_filter", KindFilter.All)
        set(v) = sp.edit { putString("kind_filter", v.name) }

    var groupBy: GroupBy
        get() = enumPref("group_by", GroupBy.None)
        set(v) = sp.edit { putString("group_by", v.name) }

    var lastBookId: String?
        get() = sp.getString("last_book", null)
        set(v) = sp.edit { putString("last_book", v) }

    var scanVersion: Int
        get() = sp.getInt("scan_version", 0)
        set(v) = sp.edit { putInt("scan_version", v) }

    var gameControls: String
        get() = sp.getString("game_controls", "swipe")!!
        set(v) = sp.edit { putString("game_controls", v) }

    var secureScreens: Boolean
        get() = sp.getBoolean("secure_screens", true)
        set(v) = sp.edit { putBoolean("secure_screens", v) }

    /** Folders whose audio files are each a separate book. */
    var splitFolders: Set<String>
        get() = sp.getStringSet("split_folders", emptySet())!!.toSet()
        set(v) = sp.edit { putStringSet("split_folders", v) }
    /** Folders whose audio (including all subfolders) is one book. */
    var mergeFolders: Set<String>
        get() = sp.getStringSet("merge_folders", emptySet())!!.toSet()
        set(v) = sp.edit { putStringSet("merge_folders", v) }

    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit { putBoolean("onboarded", v) }

    // --------------------------------------------------------------- audio
    var skipBackSec: Int
        get() = sp.getInt("skip_back", 15)
        set(v) = sp.edit { putInt("skip_back", v) }

    var skipForwardSec: Int
        get() = sp.getInt("skip_fwd", 30)
        set(v) = sp.edit { putInt("skip_fwd", v) }

    /** Seconds to rewind when resuming after a pause, so you catch the thread again. */
    var resumeRewindSec: Int
        get() = sp.getInt("resume_rewind", 5)
        set(v) = sp.edit { putInt("resume_rewind", v) }

    /** Rewind more the longer you've been away (up to 4x). */
    var smartRewind: Boolean
        get() = sp.getBoolean("smart_rewind", true)
        set(v) = sp.edit { putBoolean("smart_rewind", v) }

    var playbackSpeed: Float
        get() = sp.getFloat("speed", 1f)
        set(v) = sp.edit { putFloat("speed", v) }

    /** Lower the volume (instead of pausing) when a notification sound plays. */
    var duckForNotifications: Boolean
        get() = sp.getBoolean("duck", false)
        set(v) = sp.edit { putBoolean("duck", v) }

    var sleepMinutes: Int
        get() = sp.getInt("sleep_minutes", 30)
        set(v) = sp.edit { putInt("sleep_minutes", v) }

    var shakeToExtend: Boolean
        get() = sp.getBoolean("shake_extend", true)
        set(v) = sp.edit { putBoolean("shake_extend", v) }

    /** Next/previous from outside the app (car steering wheel, earbuds, watch): jump chapters instead of skipping seconds. */
    var chapterSkipButtons: Boolean
        get() = sp.getBoolean("external_chapter_buttons", false)
        set(v) = sp.edit { putBoolean("external_chapter_buttons", v) }

    // -------------------------------------------------------------- reader
    var readerTheme: String
        get() = sp.getString("reader_theme", "paper")!!
        set(v) = sp.edit { putString("reader_theme", v) }

    /** Default reader look (encoded ReaderSettings). Each book can override it. */
    var readerDefaults: String?
        get() = sp.getString("reader_defaults", null)
        set(v) = sp.edit { putString("reader_defaults", v) }
    fun readerFor(bookId: String): String? = sp.getString("reader_book_$bookId", null)
    fun setReaderFor(bookId: String, v: String?) = sp.edit { if (v == null) remove("reader_book_$bookId") else putString("reader_book_$bookId", v) }

    /** Reader screen brightness, 0–1, or -1 to follow the system. */
    var readerBrightness: Float
        get() = sp.getFloat("reader_brightness", -1f)
        set(v) = sp.edit { putFloat("reader_brightness", v) }

    var fontScale: Float
        get() = sp.getFloat("font_scale", 1f)
        set(v) = sp.edit { putFloat("font_scale", v) }

    var lineHeight: Float
        get() = sp.getFloat("line_height", 1.5f)
        set(v) = sp.edit { putFloat("line_height", v) }

    var margin: Int
        get() = sp.getInt("margin", 24)
        set(v) = sp.edit { putInt("margin", v) }

    var fontFamily: String
        get() = sp.getString("font_family", "publisher")!!
        set(v) = sp.edit { putString("font_family", v) }

    var justify: Boolean
        get() = sp.getBoolean("justify", true)
        set(v) = sp.edit { putBoolean("justify", v) }

    var hyphenate: Boolean
        get() = sp.getBoolean("hyphenate", true)
        set(v) = sp.edit { putBoolean("hyphenate", v) }

    var scrolled: Boolean
        get() = sp.getBoolean("scrolled", false)
        set(v) = sp.edit { putBoolean("scrolled", v) }

    /** Home screen widgets: "worm" colours or the "wallpaper" (Material You); corner roundness in dp. */
    var widgetColors: String
        get() = sp.getString("widget_colors", "worm")!!
        set(v) = sp.edit { putString("widget_colors", v) }
    var widgetCorners: Int
        get() = sp.getInt("widget_corners", 24)
        set(v) = sp.edit { putInt("widget_corners", v) }

    /** Speed reading (one word at a time): words per minute and word size. */
    var rsvpWpm: Int
        get() = sp.getInt("rsvp_wpm", 300)
        set(v) = sp.edit { putInt("rsvp_wpm", v) }
    var rsvpSize: Int
        get() = sp.getInt("rsvp_size", 40)
        set(v) = sp.edit { putInt("rsvp_size", v) }

    /** What the reading footer shows (see ReaderFooter): chapter, book%, chapterProgress, bar, clock, battery. */
    var readerFooter: Set<String>
        get() = sp.getStringSet("reader_footer", setOf("chapter", "book", "chapterProgress"))!!.toSet()
        set(v) = sp.edit { putStringSet("reader_footer", v) }

    var volumeKeysTurnPages: Boolean
        get() = sp.getBoolean("volume_pages", true)
        set(v) = sp.edit { putBoolean("volume_pages", v) }

    var keepScreenOn: Boolean
        get() = sp.getBoolean("keep_screen_on", true)
        set(v) = sp.edit { putBoolean("keep_screen_on", v) }

    // ----------------------------------------------------------- the Burrow
    /** Seconds the app may sit in the background before the Burrow relocks (0 = immediately). */
    var autoLockSeconds: Int
        get() = sp.getInt("auto_lock_seconds", 30)
        set(v) = sp.edit { putInt("auto_lock_seconds", v) }

    var biometric: Boolean
        get() = sp.getBoolean("biometric", false)
        set(v) = sp.edit { putBoolean("biometric", v) }

    // PIN (salted PBKDF2 hash; the PIN itself is never stored).
    var pinHash: String?
        get() = sp.getString("pin_hash", null)
        set(v) = sp.edit { putString("pin_hash", v) }
    var pinSalt: String?
        get() = sp.getString("pin_salt", null)
        set(v) = sp.edit { putString("pin_salt", v) }

    // ------------------------------------------------------------- sync
    var kosyncServer: String
        get() = sp.getString("kosync_server", "https://sync.koreader.rocks")!!
        set(v) = sp.edit { putString("kosync_server", v) }
    var kosyncUser: String?
        get() = sp.getString("kosync_user", null)
        set(v) = sp.edit { putString("kosync_user", v) }
    /** KOSync wants md5(password); we only ever keep that. */
    var kosyncKey: String?
        get() = sp.getString("kosync_key", null)
        set(v) = sp.edit { putString("kosync_key", v) }

    // ---------------------------------------------------------- easter egg
    var gameUnlocked: Boolean
        get() = sp.getBoolean("game_unlocked", false)
        set(v) = sp.edit { putBoolean("game_unlocked", v) }
    var gameBest: Int
        get() = sp.getInt("game_best", 0)
        set(v) = sp.edit { putInt("game_best", v) }
    var gameSkin: Int
        get() = sp.getInt("game_skin", 0)
        set(v) = sp.edit { putInt("game_skin", v) }

    var syncPullOnOpen: Boolean
        get() = sp.getBoolean("sync_pull_open", true)
        set(v) = sp.edit { putBoolean("sync_pull_open", v) }
    var syncPushWhileReading: Boolean
        get() = sp.getBoolean("sync_push_reading", true)
        set(v) = sp.edit { putBoolean("sync_push_reading", v) }

    var wormholeName: String
        get() = sp.getString("wormhole_name", null) ?: friendlyDeviceName()
        set(v) = sp.edit { putString("wormhole_name", v) }

    /** "Galaxy S24 Ultra" rather than "SM-S928U", when the phone knows its marketing name. */
    private fun friendlyDeviceName(): String =
        runCatching { android.provider.Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: android.os.Build.MODEL ?: "Worm"
}
