package com.imgviewer.data

enum class LibraryAccess { FULL, PARTIAL, NONE }

data class VolumeRevision(val version: String, val generation: Long)

data class LibraryRevision(val volumes: Map<String, VolumeRevision>)

/** Decisions contain no Android state; permission grants are checked anew by the repository. */
object LibraryRefreshPolicy {
    fun shouldReadOnResume(
        access: LibraryAccess,
        previousAccess: LibraryAccess?,
        dirty: Boolean,
        previousRevision: LibraryRevision?,
        currentRevision: LibraryRevision?,
    ): Boolean = access != LibraryAccess.FULL || previousAccess != access || dirty ||
        previousRevision == null || currentRevision == null || previousRevision != currentRevision

    fun permissionScopeReduced(previous: LibraryAccess?, current: LibraryAccess): Boolean =
        (previous == LibraryAccess.FULL && current != LibraryAccess.FULL) ||
            (previous == LibraryAccess.PARTIAL && current == LibraryAccess.NONE)

    fun needsEmptyConfirmation(access: LibraryAccess, libraryCount: Int, attempt: Int): Boolean =
        access == LibraryAccess.FULL && libraryCount == 0 && attempt == 0

    fun retryDelayMillis(attempt: Int): Long = when (attempt) { 0 -> 180L; 1 -> 450L; else -> 900L }
}

/** Requests arriving during a query are merged into one subsequent pass, never cancel its cursor. */
internal class RefreshRequestQueue {
    private var revision = 0L
    var dirty: Boolean = true
        private set

    fun invalidate() { revision++; dirty = true }
    fun begin(): Long = revision
    fun accept(startedAt: Long) { if (revision == startedAt) dirty = false }
    fun hasNewRequest(startedAt: Long): Boolean = revision != startedAt
}
