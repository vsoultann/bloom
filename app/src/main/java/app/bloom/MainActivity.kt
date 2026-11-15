package app.bloom

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import app.bloom.ui.components.LocalAvatarUrls
import app.bloom.ui.components.LocalAvatars
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import okio.Path.Companion.toOkioPath
import app.bloom.ui.screens.MainScreen
import app.bloom.ui.theme.BloomTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    /** Write any crash (with its stack trace) to files/logs/crash.log before the app dies. */
    private fun installCrashLog() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val file = java.io.File(filesDir, "logs/crash.log")
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                file.parentFile?.mkdirs()
                val trace = android.util.Log.getStackTraceString(e)
                file.appendText("=== ${System.currentTimeMillis() / 1000} thread=${t.name} app=${packageManager.getPackageInfo(packageName, 0).versionName}\n$trace\n")
            }
            previous?.uncaughtException(t, e)
        }
    }

    /** Highest refresh rate at the current resolution (120 Hz), unless battery saver is on. */
    private fun applyRefreshRate() {
        val save = getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true
        app.bloom.ui.theme.Ui.powerSave = save
        window.attributes = window.attributes.apply {
            val cur = display?.mode
            val best = display?.supportedModes
                ?.filter { it.physicalWidth == cur?.physicalWidth && it.physicalHeight == cur.physicalHeight }
                ?.maxByOrNull { it.refreshRate }
            preferredDisplayModeId = if (save) 0 else best?.modeId ?: 0
            preferredRefreshRate = if (save) 0f else best?.refreshRate ?: 0f
            if (android.os.Build.VERSION.SDK_INT >= 35) {
                // don't let the system drop our refresh rate while idle (that made the first
                // frames of every swipe miss), and boost instantly on touch; battery saver wins
                setFrameRatePowerSavingsBalanced(save)
                setFrameRateBoostOnTouchEnabled(true)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppViewModel.visible = true
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        openFrom(intent)
    }

    /** Tapped a message notification: open that chat once the app is ready. */
    private fun openFrom(intent: android.content.Intent?) {
        val ch = intent?.getLongExtra(app.bloom.notify.Notifier.EXTRA_CHANNEL, 0L) ?: 0L
        if (ch == 0L) return
        val msg = intent?.getLongExtra(app.bloom.notify.Notifier.EXTRA_MESSAGE, 0L) ?: 0L
        intent?.removeExtra(app.bloom.notify.Notifier.EXTRA_CHANNEL)
        lifecycleScope.launch {
            while (!vm.ready) kotlinx.coroutines.delay(100)
            vm.openFromNotification(ch, msg)
        }
    }

    override fun onStop() {
        super.onStop()
        AppViewModel.visible = false
        // keep the instant-start snapshot current
        vm.saveCacheNow()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installCrashLog()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // One image loader for the app: network via OkHttp, animated GIF/WebP, gentle crossfade.
        SingletonImageLoader.setSafe { ctx ->
            ImageLoader.Builder(ctx)
                .components {
                    // Root's image URLs are re-signed every session; key the cache on the
                    // stable part so avatars/icons load instantly from disk next time.
                    add(app.bloom.ui.components.StableUrlKeyer())
                    add(OkHttpNetworkFetcherFactory())
                    add(AnimatedImageDecoder.Factory())
                    add(coil3.video.VideoFrameDecoder.Factory())
                }
                .memoryCache { MemoryCache.Builder().maxSizePercent(ctx, 0.25).build() }
                .diskCache { DiskCache.Builder().directory(ctx.cacheDir.resolve("images").toOkioPath()).maxSizeBytes(512L * 1024 * 1024).build() }
                .crossfade(true)
                .build()
        }
        openFrom(intent)
        // Android 13+: message notifications need permission
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 41)
        }
        applyRefreshRate()
        // battery saver: step effects down and stop forcing 120 Hz
        registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: android.content.Intent?) = applyRefreshRate()
        }, android.content.IntentFilter(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        setContent {
            val meId = vm.me?.id
            val pic = vm.myAvatar
            val avatars = remember(meId, pic) { if (meId != null && pic != null) mapOf(meId to pic) else emptyMap() }
            val avatarUrls = remember(vm.users.toMap()) { vm.users.mapNotNull { (id, u) -> u.avatarUrl?.let { id to it } }.toMap() }
            // calls ask for the microphone first, then continue
            val micAsk = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) vm.voice.retryJoin() else { vm.voice.needsMic = null; vm.error = "Bloom needs the microphone for calls" }
            }
            val wantsMic = vm.voice.needsMic
            androidx.compose.runtime.LaunchedEffect(wantsMic) { if (wantsMic != null) micAsk.launch(android.Manifest.permission.RECORD_AUDIO) }
            // Customize → hide status bar
            val hideBars = app.bloom.ui.theme.Ui.hideStatusBar
            androidx.compose.runtime.LaunchedEffect(hideBars) {
                val c = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (hideBars) c.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars()) else c.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            }
            BloomTheme(vm.look, vm.amoled, vm.currentOverrides, vm.bgShapes) {
                CompositionLocalProvider(LocalAvatars provides avatars, LocalAvatarUrls provides avatarUrls) { MainScreen(vm) }
            }
        }
    }
}
