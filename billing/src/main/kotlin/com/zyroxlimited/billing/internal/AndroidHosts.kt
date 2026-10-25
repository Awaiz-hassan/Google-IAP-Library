package com.zyroxlimited.billing.internal

import android.app.Activity
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.lang.ref.WeakReference

/** Holds the purchase Activity weakly so a recreated or destroyed Activity is never leaked or used. */
internal class ActivityLaunchHost(activity: Activity) : LaunchHost {
    private val reference = WeakReference(activity)

    val activity: Activity?
        get() = reference.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    override val isUsable: Boolean get() = activity != null
}

/** Calls [onForeground] each time the app process comes to the foreground. */
internal class AppForegroundObserver(private val onForeground: () -> Unit) : DefaultLifecycleObserver {

    override fun onStart(owner: LifecycleOwner) = onForeground()

    fun register() = onMainThread { ProcessLifecycleOwner.get().lifecycle.addObserver(this) }

    fun unregister() = onMainThread { ProcessLifecycleOwner.get().lifecycle.removeObserver(this) }

    // Lifecycle observers must be added and removed on the main thread.
    private fun onMainThread(block: () -> Unit) {
        val main = Looper.getMainLooper()
        if (Looper.myLooper() == main) block() else Handler(main).post(block)
    }
}
