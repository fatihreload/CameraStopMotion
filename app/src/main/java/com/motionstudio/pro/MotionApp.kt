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
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this).components { add(VideoFrameDecoder.Factory()) }.build()
}
