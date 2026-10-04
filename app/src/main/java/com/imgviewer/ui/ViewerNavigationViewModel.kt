package com.imgviewer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/** A 32,000-photo browsing sequence belongs in memory, not Activity saved-state transactions. */
internal class ViewerNavigationViewModel : ViewModel() {
    var uris by mutableStateOf<List<String>>(emptyList())
}
