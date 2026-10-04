package com.imgviewer.data

import androidx.core.content.FileProvider

/** Own provider class avoids the device-specific issues documented for a direct FileProvider. */
class ImageShareProvider : FileProvider()
