package dev.meshmind.app

import android.app.Application

class MeshMindApp : Application() {
    lateinit var runtime: MeshRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        runtime = MeshRuntime(this)
    }
}
