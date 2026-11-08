package app.orbit.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import app.orbit.tv.core.Receiver

class OrbitApp : Application(), ImageLoaderFactory {
    lateinit var receiver: Receiver
        private set

    override fun onCreate() {
        super.onCreate()
        receiver = Receiver(this)
        receiver.start()
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(VideoFrameDecoder.Factory()) }
        .crossfade(true)
        .build()
}
