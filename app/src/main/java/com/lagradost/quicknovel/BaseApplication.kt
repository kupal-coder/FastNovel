package com.lagradost.quicknovel

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import androidx.work.Configuration
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.lagradost.cloudstream3.utils.ImageLoader
import com.lagradost.quicknovel.DataStore.getKey
import com.lagradost.quicknovel.DataStore.getKeys
import com.lagradost.quicknovel.DataStore.removeKey
import com.lagradost.quicknovel.DataStore.removeKeys
import com.lagradost.quicknovel.DataStore.setKey
import com.lagradost.quicknovel.auth.LoginActivity
import com.lagradost.quicknovel.auth.ReadingStats
import com.lagradost.quicknovel.auth.SupabaseAuth
import java.lang.ref.WeakReference

class BaseApplication : Application(), SingletonImageLoader.Factory, Configuration.Provider  {
    /** Number of started activities, 0 means the app is in the background. */
    private var startedActivities = 0

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        context = base
    }

    override fun onCreate() {
        super.onCreate()

        // Uploads anything that could not be uploaded when the app was closed last time.
        ReadingStats.flushAsync(this)

        // Shows the login screen on top of MainActivity instead of touching MainActivity itself.
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                val showLogin = activity is MainActivity &&
                        savedInstanceState == null &&
                        SupabaseAuth.shouldShowLogin(activity)
                if (showLogin) {
                    activity.startActivity(Intent(activity, LoginActivity::class.java))
                }
            }

            override fun onActivityStarted(activity: Activity) {
                startedActivities++
            }

            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                // No activity is visible anymore, the app went to the background.
                if (startedActivities == 0) {
                    ReadingStats.flushAsync(activity)
                }
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    override fun newImageLoader(context: PlatformContext): coil3.ImageLoader {
        // Coil Module will be initialized & setSafe globally when first loadImage() is invoked
        return ImageLoader.buildImageLoader(applicationContext)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()


    companion object {
        /** Use to get activity from Context */
        tailrec fun Context.getActivity(): Activity? = this as? Activity
            ?: (this as? ContextWrapper)?.baseContext?.getActivity()

        private var _context: WeakReference<Context>? = null
        var context
            get() = _context?.get()
            set(value) {
                _context = WeakReference(value)
            }

        fun removeKeys(folder: String): Int? {
            return context?.removeKeys(folder)
        }

        fun <T> setKey(path: String, value: T) {
            context?.setKey(path, value)
        }

        fun <T> setKey(folder: String, path: String, value: T) {
            context?.setKey(folder, path, value)
        }

        inline fun <reified T : Any> getKey(path: String, defVal: T?): T? {
            return context?.getKey(path, defVal)
        }

        inline fun <reified T : Any> getKey(path: String): T? {
            return context?.getKey(path)
        }

        fun <T : Any> getKeyClass(path: String, valueType: Class<T>): T? {
            return context?.getKey(path, valueType)
        }

        fun <T : Any> setKeyClass(path: String, value: T) {
            context?.setKey(path, value)
        }

        inline fun <reified T : Any> getKey(folder: String, path: String): T? {
            return context?.getKey(folder, path)
        }

        inline fun <reified T : Any> getKey(folder: String, path: String, defVal: T?): T? {
            return context?.getKey(folder, path, defVal)
        }

        fun getKeys(folder: String): List<String>? {
            return context?.getKeys(folder)
        }

        fun removeKey(folder: String, path: String) {
            context?.removeKey(folder, path)
        }

        fun removeKeyClass(path: String) {
            context?.removeKey(path)
        }

        fun removeKey(path: String) {
            context?.removeKey(path)
        }
    }
}