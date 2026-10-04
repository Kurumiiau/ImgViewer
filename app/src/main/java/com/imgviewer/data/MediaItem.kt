package com.imgviewer.data

import android.net.Uri

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val dateTaken: Long,
    val albumId: String,
    val albumName: String,
    val width: Int,
    val height: Int,
    val size: Long,
    val isAnimatedCandidate: Boolean,
    val isFavorite: Boolean = false,
)

/** MediaStore DATE_TAKEN is milliseconds; DATE_ADDED / DATE_MODIFIED are seconds. */
object MediaDates {
    fun chooseDate(dateTakenMs: Long, dateModifiedSeconds: Long, dateAddedSeconds: Long): Long =
        when {
            dateTakenMs > 0 -> dateTakenMs
            dateModifiedSeconds > 0 -> secondsToMillis(dateModifiedSeconds)
            dateAddedSeconds > 0 -> secondsToMillis(dateAddedSeconds)
            else -> 0L
        }

    private fun secondsToMillis(seconds: Long): Long =
        if (seconds > Long.MAX_VALUE / 1_000L) Long.MAX_VALUE else seconds * 1_000L
}
