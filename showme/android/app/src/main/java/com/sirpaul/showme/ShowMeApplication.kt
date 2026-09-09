package com.sirpaul.showme
import android.app.Application
import org.opencv.android.OpenCVLoader
class ShowMeApplication : Application() {
    override fun onCreate() { super.onCreate(); openCvReady = OpenCVLoader.initLocal() }
    companion object { @Volatile var openCvReady = false; private set }
}
