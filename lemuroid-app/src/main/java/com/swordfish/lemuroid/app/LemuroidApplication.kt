package com.swordfish.lemuroid.app

import android.annotation.SuppressLint
import android.content.Context
import androidx.startup.AppInitializer
import androidx.work.ListenableWorker
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.google.android.material.color.DynamicColors
import com.swordfish.lemuroid.app.shared.covers.CoverUtils
import com.swordfish.lemuroid.app.shared.startup.GameProcessInitializer
import com.swordfish.lemuroid.app.shared.startup.MainProcessInitializer
import com.swordfish.lemuroid.app.utils.android.isMainProcess
import com.swordfish.lemuroid.common.coroutines.safeLaunch
import com.swordfish.lemuroid.ext.feature.context.ContextHandler
import com.swordfish.lemuroid.lib.injection.HasWorkerInjector
import com.swordfish.lemuroid.lib.storage.SaveStorageManager
import dagger.Lazy
import dagger.android.AndroidInjector
import dagger.android.DispatchingAndroidInjector
import dagger.android.support.DaggerApplication
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.filter
import javax.inject.Inject

class LemuroidApplication : DaggerApplication(), HasWorkerInjector, ImageLoaderFactory {
    @Inject
    lateinit var workerInjector: DispatchingAndroidInjector<ListenableWorker>

    @Inject
    lateinit var saveStorageManager: Lazy<SaveStorageManager>

    @OptIn(DelicateCoroutinesApi::class)
    @SuppressLint("CheckResult")
    override fun onCreate() {
        super.onCreate()

        val initializeComponent =
            if (isMainProcess()) {
                GlobalScope.safeLaunch {
                    saveStorageManager.get().observeEnabled()
                        .filter { it }
                        .collect { saveStorageManager.get().syncAll() }
                }
                MainProcessInitializer::class.java
            } else {
                GameProcessInitializer::class.java
            }

        AppInitializer.getInstance(this).initializeComponent(initializeComponent)

        DynamicColors.applyToActivitiesIfAvailable(this)
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        ContextHandler.attachBaseContext(base)
    }

    override fun applicationInjector(): AndroidInjector<out DaggerApplication> {
        return DaggerLemuroidApplicationComponent.builder().create(this)
    }

    override fun workerInjector(): AndroidInjector<ListenableWorker> = workerInjector

    override fun newImageLoader(): ImageLoader {
        return CoverUtils.buildImageLoader(applicationContext)
    }
}
