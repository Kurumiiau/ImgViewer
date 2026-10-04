package com.imgviewer.image

import android.content.Context
import coil.ImageLoader
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder
import coil.memory.MemoryCache
import coil.request.CachePolicy
import kotlinx.coroutines.Dispatchers

/** Animated WebP/GIF retain a frame clock; Android still decodes their image bitstreams. */
object ImageLoaderFactory {
    @Volatile private var fullLoader: ImageLoader? = null
    @Volatile private var thumbnailLoader: ImageLoader? = null

    fun create(context: Context): ImageLoader = fullLoader ?: synchronized(this) {
        fullLoader ?: build(context.applicationContext, animated = true).also { fullLoader = it }
    }

    internal fun thumbnails(context: Context): ImageLoader = thumbnailLoader ?: synchronized(this) {
        thumbnailLoader ?: build(context.applicationContext, animated = false).also { thumbnailLoader = it }
    }

    private fun build(context: Context, animated: Boolean): ImageLoader = ImageLoader.Builder(context)
        .components {
            if (animated) {
                add(FrameSequenceDecoder.Factory())
                add(ImageDecoderDecoder.Factory())
            }
            add(SvgDecoder.Factory())
            add(ExtraImageDecoder.Factory())
        }
        .memoryCache {
            MemoryCache.Builder(context)
                .maxSizeBytes(minOf(Runtime.getRuntime().maxMemory() / 8, 48L * 1_024 * 1_024).toInt())
                .build()
        }
        .diskCache(null) // Local media stays in place; do not duplicate the gallery into cache.
        .memoryCachePolicy(if (animated) CachePolicy.DISABLED else CachePolicy.ENABLED)
        .decoderDispatcher(Dispatchers.IO.limitedParallelism(2))
        .bitmapFactoryMaxParallelism(2)
        .crossfade(false)
        .networkObserverEnabled(false)
        .build()
}
