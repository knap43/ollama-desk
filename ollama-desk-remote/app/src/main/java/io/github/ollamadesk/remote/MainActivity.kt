package io.github.ollamadesk.remote

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import io.github.ollamadesk.remote.data.Store
import io.github.ollamadesk.remote.ui.App
import io.github.ollamadesk.remote.ui.RemoteTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // light icons on the dark bars, whatever the phone's own theme is
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        handle(intent)
        setContent {
            RemoteTheme {
                App(vm)
            }
        }
        val store = Store(this)
        if (Build.VERSION.SDK_INT >= 33 && !store.askedNotifications) {
            store.askedNotifications = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.resume()
    }

    /** A notification opens a chat; an ollamadesk://pair link starts pairing. */
    private fun handle(intent: Intent?) {
        intent ?: return
        val data = intent.data
        if (data != null && data.scheme == "ollamadesk" && vm.server.value == null) {
            vm.pair(data.toString())
        }
        intent.getStringExtra(EXTRA_CHAT)?.let { vm.openChat(it) }
    }

    companion object {
        const val EXTRA_CHAT = "chat"
    }
}
