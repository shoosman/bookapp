package com.shoosman.bookapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.shoosman.bookapp.ui.common.ReportDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.shoosman.bookapp.data.AppSettings
import com.shoosman.bookapp.ui.book.BookScreen
import com.shoosman.bookapp.ui.home.MainScreen
import com.shoosman.bookapp.ui.player.PlayerScreen
import com.shoosman.bookapp.ui.reader.ReaderScreen
import com.shoosman.bookapp.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as App
        setContent {
            val settings by app.settings.flow.collectAsStateWithLifecycle(initialValue = AppSettings())
            AppTheme(settings.theme) {
                val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
                LaunchedEffect(Unit) {
                    app.player.connect()
                    if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                AppNav(settings)
                var crash by remember { mutableStateOf(CrashLog.pending()) }
                crash?.let { report ->
                    ReportDialog(
                        title = "Приложение вылетело",
                        intro = "В прошлый раз Чтец закрылся с ошибкой. Скопируйте отчёт и пришлите его, чтобы ошибку исправили.",
                        report = report,
                        onDismiss = {
                            CrashLog.clearPending()
                            crash = null
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AppNav(settings: AppSettings) {
    val nav = rememberNavController()
    DisposableEffect(nav) {
        val listener = androidx.navigation.NavController.OnDestinationChangedListener { _, dest, args ->
            val params = args?.keySet()?.filter { it != "android-support-nav:controller:deepLinkIntent" }
                ?.joinToString { "$it=${args.get(it)}" }.orEmpty()
            CrashLog.note("Экран ${dest.route} $params")
        }
        nav.addOnDestinationChangedListener(listener)
        onDispose { nav.removeOnDestinationChangedListener(listener) }
    }
    NavHost(navController = nav, startDestination = "main") {
        composable("main") {
            MainScreen(
                settings = settings,
                onOpenBook = { nav.navigate("book/$it") },
                onRead = { nav.navigate("reader/$it") },
                onListen = { nav.navigate("player/$it") },
            )
        }
        composable("book/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments?.getLong("id") ?: return@composable
            BookScreen(
                bookId = id,
                settings = settings,
                onBack = { nav.popBackStack() },
                onRead = { pos -> nav.navigate("reader/$id?pos=$pos") },
                onListen = { ms -> nav.navigate("player/$id?ms=$ms") },
            )
        }
        composable(
            "reader/{id}?pos={pos}",
            arguments = listOf(
                navArgument("id") { type = NavType.LongType },
                navArgument("pos") { type = NavType.IntType; defaultValue = -1 },
            ),
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: return@composable
            val pos = entry.arguments?.getInt("pos") ?: -1
            ReaderScreen(
                bookId = id,
                startPos = pos,
                settings = settings,
                onBack = { nav.popBackStack() },
                onOpenPlayer = {
                    nav.popBackStack()
                    nav.navigate("player/$id")
                },
            )
        }
        composable(
            "player/{id}?ms={ms}",
            arguments = listOf(
                navArgument("id") { type = NavType.LongType },
                navArgument("ms") { type = NavType.LongType; defaultValue = -1L },
            ),
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: return@composable
            val ms = entry.arguments?.getLong("ms") ?: -1L
            PlayerScreen(
                bookId = id,
                startMs = ms,
                settings = settings,
                onBack = { nav.popBackStack() },
                onReadAt = { pos ->
                    nav.popBackStack()
                    nav.navigate("reader/$id?pos=$pos")
                },
            )
        }
    }
}
