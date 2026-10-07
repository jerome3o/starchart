package io.github.jerome3o.starchart

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * Shell activity: bottom tabs for the star chart, chat, the map and settings.
 * The fragments are created once and shown/hidden so the map keeps its
 * state when switching tabs.
 */
class MainActivity : AppCompatActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, R.string.notifications_denied, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Notifications.ensureChannel(this)
        Notifications.turnOffRecurringOnce(this)
        if (Build.VERSION.SDK_INT >= 33 && !Permissions.canNotify(this)) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.fragment_container, StarChartFragment(), TAG_STARCHART)
                .add(R.id.fragment_container, ChatFragment(), TAG_CHAT)
                .add(R.id.fragment_container, MapFragment(), TAG_MAP)
                .add(R.id.fragment_container, SettingsFragment(), TAG_SETTINGS)
                .commitNow()
        }

        val nav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        nav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }
        nav.selectedItemId = Prefs.get(this).getInt(Prefs.KEY_LAST_TAB, R.id.nav_starchart)
        showTab(nav.selectedItemId)

        handlePairIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Tracking survives reboots and app updates only via BootReceiver, which
        // needs background location; opening the app is the reliable fallback.
        val trackingEnabled = Prefs.get(this).getBoolean(Prefs.KEY_TRACKING_ENABLED, false) &&
            Permissions.hasLocation(this)
        if (trackingEnabled && !LocationService.running) {
            LocationService.start(this, "app_opened")
        }
        PhoneCommands.pollAsync(this)
    }

    private fun showTab(itemId: Int) {
        val selected = when (itemId) {
            R.id.nav_chat -> TAG_CHAT
            R.id.nav_map -> TAG_MAP
            R.id.nav_settings -> TAG_SETTINGS
            else -> TAG_STARCHART
        }
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        for (tag in listOf(TAG_STARCHART, TAG_CHAT, TAG_MAP, TAG_SETTINGS)) {
            val fragment = fm.findFragmentByTag(tag) ?: continue
            if (tag == selected) tx.show(fragment) else tx.hide(fragment)
        }
        tx.commit()
        Prefs.get(this).edit().putInt(Prefs.KEY_LAST_TAB, itemId).apply()
    }

    private fun handlePairIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "starchart" || data.host != "pair") return
        val token = data.getQueryParameter("token")
        if (token.isNullOrEmpty()) return
        Sync.storeToken(this, token)
        SyncWorker.schedulePeriodic(this)
        SyncWorker.syncNow(this)
        Toast.makeText(this, R.string.sync_linked_toast, Toast.LENGTH_LONG).show()
        findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId = R.id.nav_settings
    }

    companion object {
        private const val TAG_STARCHART = "starchart"
        private const val TAG_CHAT = "chat"
        private const val TAG_MAP = "map"
        private const val TAG_SETTINGS = "settings"
    }
}
