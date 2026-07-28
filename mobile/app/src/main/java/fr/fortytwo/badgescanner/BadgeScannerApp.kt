package fr.fortytwo.badgescanner

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder

/** Registers the SVG decoder so coalition logos (SVG) render in AsyncImage. */
class BadgeScannerApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(SvgDecoder.Factory()) }
            .build()
}
