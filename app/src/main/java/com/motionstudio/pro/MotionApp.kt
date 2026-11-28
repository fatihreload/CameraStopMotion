package com.motionstudio.pro

import android.app.Application
import androidx.room.Room
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.motionstudio.pro.data.AppDb
import com.motionstudio.pro.data.ProjectRepository

class MotionApp : Application(), ImageLoaderFactory {
    val repo: ProjectRepository by lazy {
        ProjectRepository(Room.databaseBuilder(this, AppDb::class.java, "motion.db").build().projects())
    }
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { java.io.File(filesDir, "crash.txt").writeText(android.util.Log.getStackTraceString(e)) } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this).components { add(VideoFrameDecoder.Factory()) }.build()
}
