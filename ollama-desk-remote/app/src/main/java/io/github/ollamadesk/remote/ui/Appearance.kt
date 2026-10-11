package io.github.ollamadesk.remote.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.ollamadesk.remote.R

/** Launcher icons. Each is an <activity-alias> in the manifest; exactly one is enabled. */
enum class AppIcon(val alias: String, val label: String, val res: Int, val enabledByDefault: Boolean) {
    CLASSIC("io.github.ollamadesk.remote.LauncherClassic", "Classic", R.mipmap.ic_launcher, true),
    COLORFUL("io.github.ollamadesk.remote.LauncherColorful", "Colorful", R.mipmap.ic_launcher_colorful, false),
    SYSTEM("io.github.ollamadesk.remote.LauncherSystem", "System", R.mipmap.ic_launcher_system, false),
}

/** The app icon and whether the UI takes its colors from the wallpaper (Material You). */
object Appearance {
    private const val PREFS = "appearance"
    private const val KEY_SYSTEM_COLORS = "system_colors"

    /** Wallpaper colors exist from Android 12. */
    val systemColorsAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    private val systemColors = MutableStateFlow(false)
    private var loaded = false

    fun systemColors(context: Context): StateFlow<Boolean> {
        if (!loaded) {
            systemColors.value = systemColorsAvailable && prefs(context).getBoolean(KEY_SYSTEM_COLORS, false)
            loaded = true
        }
        return systemColors.asStateFlow()
    }

    fun setSystemColors(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_SYSTEM_COLORS, on).apply()
        systemColors.value = on && systemColorsAvailable
        loaded = true
    }

    fun icons(): List<AppIcon> =
        if (systemColorsAvailable) AppIcon.entries else AppIcon.entries - AppIcon.SYSTEM

    fun icon(context: Context): AppIcon {
        val pm = context.packageManager
        return AppIcon.entries.firstOrNull { icon ->
            when (pm.getComponentEnabledSetting(component(context, icon))) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
                else -> icon.enabledByDefault
            }
        } ?: AppIcon.CLASSIC
    }

    fun setIcon(context: Context, icon: AppIcon) {
        val pm = context.packageManager
        // Enable the new entry first so the app is never left without one in the launcher.
        pm.setComponentEnabledSetting(
            component(context, icon),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        AppIcon.entries.filter { it != icon }.forEach {
            pm.setComponentEnabledSetting(
                component(context, it),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    private fun component(context: Context, icon: AppIcon) = ComponentName(context.packageName, icon.alias)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

@Composable
fun AppearanceDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var icon by remember { mutableStateOf(Appearance.icon(context)) }
    val systemColors by Appearance.systemColors(context).collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Appearance") },
        text = {
            Column {
                Text("App icon", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Appearance.icons().forEach { option ->
                        IconChoice(option, selected = option == icon, modifier = Modifier.weight(1f)) {
                            if (option != icon) {
                                Appearance.setIcon(context, option)
                                icon = option
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Some launchers take a moment to update, or drop the icon from the home screen; " +
                        "add it back from the app drawer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (Appearance.systemColorsAvailable) {
                    Spacer(Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .toggleable(
                                value = systemColors,
                                role = Role.Switch,
                                onValueChange = { Appearance.setSystemColors(context, it) },
                            )
                            .padding(vertical = 8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Use system colors", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Take the app's colors from your wallpaper",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.size(12.dp))
                        Switch(checked = systemColors, onCheckedChange = null)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun IconChoice(option: AppIcon, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    val image = remember(option) {
        requireNotNull(context.getDrawable(option.res)).toBitmap(192, 192).asImageBitmap()
    }
    val shape = MaterialTheme.shapes.medium
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(shape)
            .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
    ) {
        Image(image, contentDescription = null, modifier = Modifier.size(48.dp).clip(CircleShape))
        Spacer(Modifier.height(6.dp))
        Text(
            option.label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
