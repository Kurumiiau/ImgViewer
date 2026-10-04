package com.imgviewer.data

import java.io.IOException

data class MediaLibrarySnapshot(
    val libraryImages: List<MediaItem>,
    val importedImages: List<MediaItem>,
    val access: LibraryAccess,
    val revision: LibraryRevision?,
) {
    val images: List<MediaItem>
        get() = (libraryImages + importedImages).distinctBy { it.uri }
            .sortedWith(compareByDescending<MediaItem> { it.dateTaken }.thenBy { it.name }.thenBy { it.uri.toString() })
}

class MediaProviderUnavailableException(message: String, cause: Throwable? = null) : IOException(message, cause)

class LibraryAccessChangedException(val access: LibraryAccess) : IOException("照片访问范围已改变，正在重新读取。")
