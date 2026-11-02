package com.nuvio.tv.core.plugin.cloudstream

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.CommonActivity
import com.nuvio.tv.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Opens a CloudStream extension's own settings screen. Extensions build them as AppCompat fragments and
 * Material bottom sheets on the activity they're handed, so they get this see-through AppCompat activity;
 * it closes when the extension's last screen does.
 */
@AndroidEntryPoint
class ExtensionSettingsActivity : AppCompatActivity() {
    @Inject lateinit var loader: ExternalExtensionLoader

    private val watcher = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) {
            if (fm.fragments.none { it !== f && !it.isRemoving }) finish()
        }
    }

    /** The app's activity, which extensions get back once this closes. */
    private var previousActivity: android.app.Activity? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val scraperId = intent.getStringExtra(EXTRA_SCRAPER_ID)
        val openSettings = scraperId?.let { loader.load(it)?.plugin?.openSettings }
        if (openSettings == null) {
            failed(null)
            return
        }
        supportFragmentManager.registerFragmentLifecycleCallbacks(watcher, false)
        previousActivity = CommonActivity.activity ?: AcraApplication.getActivity()
        CommonActivity.activity = this
        AcraApplication.setActivity(this)
        try {
            openSettings(this)
        } catch (e: Throwable) {
            failed(e)
            return
        }
    }

    override fun onDestroy() {
        // Back to the app's activity: left pointing here (or at nothing), extensions' later toasts and dialogs went
        // to a closed screen.
        val previous = previousActivity?.takeUnless { it.isFinishing || it.isDestroyed }
        if (CommonActivity.activity === this) CommonActivity.activity = previous
        if (AcraApplication.getActivity() === this) AcraApplication.setActivity(previous)
        super.onDestroy()
    }

    private fun failed(e: Throwable?) {
        Log.w(TAG, "Couldn't open extension settings: ${e?.message}", e)
        Toast.makeText(this, R.string.plugin_extension_settings_failed, Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        private const val TAG = "ExtensionSettings"
        private const val EXTRA_SCRAPER_ID = "scraper_id"

        fun open(context: Context, scraperId: String) {
            context.startActivity(
                Intent(context, ExtensionSettingsActivity::class.java)
                    .putExtra(EXTRA_SCRAPER_ID, scraperId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
