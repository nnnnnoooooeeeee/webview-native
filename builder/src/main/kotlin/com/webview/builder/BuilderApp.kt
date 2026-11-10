package com.webview.builder

import android.app.Application
import com.webview.builder.build.BuildManager
import com.webview.builder.data.ProjectStore
import com.webview.builder.data.Settings
import com.webview.builder.sign.SigningKeys

//========== APPLICATION ==========

/** Holds the app-wide objects, so a running build survives screen rotation. */
class BuilderApp : Application() {

    lateinit var settings: Settings
        private set
    lateinit var projects: ProjectStore
        private set
    lateinit var keys: SigningKeys
        private set
    lateinit var builds: BuildManager
        private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        projects = ProjectStore(this)
        keys = SigningKeys(this)
        builds = BuildManager(this, projects, keys, settings)
    }
}
