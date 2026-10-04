package com.imgviewer.data

/** Android 16 / target 36 accepts at most 2,000 item URIs in one system delete request. */
internal const val MEDIA_DELETE_REQUEST_LIMIT = 2000

internal fun <T> mediaDeleteChunks(items: List<T>): List<List<T>> = items.chunked(MEDIA_DELETE_REQUEST_LIMIT)
