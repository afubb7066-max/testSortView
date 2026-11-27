package com.mohammadjafari.sortview

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mohammadjafari.sortview.data.AppConfigStore
import com.mohammadjafari.sortview.ui.FolderPickerScreen
import com.mohammadjafari.sortview.ui.SortScreen
import com.mohammadjafari.sortview.ui.SortViewModel
import com.mohammadjafari.sortview.ui.theme.SortThemes
import com.mohammadjafari.sortview.ui.theme.SortViewTheme
import java.util.Locale

/**
 * SortView Android port — Phase 1 core (folder scan, image grid, category
 * shortcuts 0-9, safe transfers, undo). The desktop behavioral reference
 * lives in the upstream repository; ROADMAP.md maps every ported function.
 */
class MainActivity : ComponentActivity() {

    /**
     * config.json "language" wins over the device language, otherwise the
     * system language applies (desktop app_i18n.load_saved_language parity).
     */
    override fun attachBaseContext(newBase: Context) {
        val lang = AppConfigStore.peekLanguage(newBase)
        val base = if (lang.isEmpty()) newBase else {
            val cfg = Configuration(newBase.resources.configuration)
            cfg.setLocale(Locale(lang))
            newBase.createConfigurationContext(cfg)
        }
        super.attachBaseContext(base)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Phase 5 splash (core-splashscreen): must be installed before
        // super.onCreate — Theme.SortView.Starting hands over to
        // Theme.SortView right after (res/values/themes.xml).
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            // Phase 4: the palette comes from config.json ("theme", desktop
            // default "dark") through the ViewModel state; unknown values
            // fall back to dark exactly like the desktop apply_theme
            // (main.py L2452-2453). The user picks one of the six desktop
            // themes in the header menu (SortScreen).
            val vm: SortViewModel = viewModel()
            val st by vm.state.collectAsState()
            SortViewTheme(themeKey = st.theme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RootApp(vm)
                }
            }
        }
    }
}

/**
 * API 30+: the "All files access" special permission (MANAGE_EXTERNAL_STORAGE).
 * API <= 29: the classic runtime READ/WRITE pair.
 */
fun hasStorageAccess(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

@Composable
fun RootApp(vm: SortViewModel = viewModel()) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasStorageAccess(context)) }

    // Android 13+: the transfer progress notification needs POST_NOTIFICATIONS
    // (declared in AndroidManifest.xml). Ask once as soon as file access is
    // granted — a denial is non-fatal: transfers keep running in the
    // foreground service, only the visible progress notification with its
    // Stop button is hidden (review follow-up, 0.4.2).
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(granted) {
        if (granted &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Re-check when the user returns from the all-files-access settings page.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = hasStorageAccess(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (granted) {
        val st by vm.state.collectAsState()
        if (st.showFolderPicker) {
            FolderPickerScreen(vm)
        } else {
            SortScreen(vm)
        }
    } else {
        PermissionGate { granted = hasStorageAccess(context) }
    }
}

@Composable
fun PermissionGate(onRecheck: () -> Unit) {
    val context = LocalContext.current
    val legacyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        onRecheck()
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.perm_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.perm_body), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(20.dp))
        Button(onClick = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        )
                    )
                } catch (_: Exception) {
                    try {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    } catch (_: Exception) {
                    }
                }
            } else {
                legacyLauncher.launch(
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    )
                )
            }
        }) {
            Text(stringResource(R.string.perm_grant))
        }
    }
}
