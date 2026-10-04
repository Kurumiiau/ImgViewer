package com.imgviewer.data

/** The gallery read boundary also lets device tests reproduce an unavailable photo provider. */
interface MediaLibraryReader {
    fun currentAccess(): LibraryAccess
    suspend fun currentRevision(): LibraryRevision?
    suspend fun loadSnapshot(): MediaLibrarySnapshot
    suspend fun loadImportedImages(): List<MediaItem>
    suspend fun knownImageStillVisible(previous: List<MediaItem>): Boolean
}
