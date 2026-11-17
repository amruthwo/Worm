package com.worm.app.util

import java.util.Locale

fun formatDuration(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var i = 0
    while (value >= 1024 && i < units.lastIndex) { value /= 1024; i++ }
    return if (value >= 100) String.format(Locale.US, "%.0f %s", value, units[i])
    else String.format(Locale.US, "%.1f %s", value, units[i])
}

fun plural(n: Int, one: String, many: String = one + "s") = if (n == 1) "1 $one" else "$n $many"

/** "12h 4m" style for long listening times. */
fun formatHours(ms: Long): String {
    val mins = ms.coerceAtLeast(0) / 60_000
    val h = mins / 60
    val m = mins % 60
    return when {
        h >= 1 -> "${h}h ${m}m"
        else -> "${m}m"
    }
}

/** MediaMetadataRetriever is only AutoCloseable on Android 10+; this releases it on every version. */
inline fun <T> withRetriever(path: String, block: (android.media.MediaMetadataRetriever) -> T): T {
    val r = android.media.MediaMetadataRetriever()
    try {
        r.setDataSource(path)
        return block(r)
    } finally {
        runCatching { r.release() }
    }
}
