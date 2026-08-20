package com.subflow.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.subflow.R
import com.subflow.optimization.DeviceProfiler
import com.subflow.ui.screens.FavoritesScreen
import com.subflow.ui.screens.HomeScreen
import com.subflow.ui.screens.Onboarding
import com.subflow.ui.screens.OnboardingScreen
import com.subflow.ui.screens.ProgressScreen
import com.subflow.ui.screens.ReleaseInputScreen
import com.subflow.ui.screens.ResultScreen
import com.subflow.ui.screens.SettingsScreen
import com.subflow.ui.screens.StatsScreen
import com.subflow.ui.theme.SubFlowColors
import com.subflow.ui.theme.SubFlowTheme

class MainActivity : ComponentActivity() {

    companion object {
        // nav route for widget/shortcut entry points
        const val EXTRA_NAV = "subflow_nav"
    }

    private val viewModel: SearchViewModel by viewModels()

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // only on a fresh launch. rotation would re-run OCR and wipe the user's edits
        if (savedInstanceState == null) {
            handleShareIntent(intent)
            handleNavIntent(intent)
        }

        setContent {
            SubFlowTheme {
                val bg = SubFlowColors.Background
                SideEffect {
                    @Suppress("DEPRECATION")
                    window.statusBarColor = bg.toArgb()
                    @Suppress("DEPRECATION")
                    window.navigationBarColor = bg.toArgb()
                }
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(SubFlowColors.Background),
                    color = SubFlowColors.Background
                ) {
                    SubFlowNavHost(viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
        handleNavIntent(intent)
    }

    private fun handleNavIntent(intent: Intent?) {
        intent?.getStringExtra(EXTRA_NAV)?.let { route ->
            intent.removeExtra(EXTRA_NAV) // consume so it doesn't re-fire
            viewModel.requestNav(route)
        }
    }

    // inbound share intent: screenshot, video, or .torrent
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        @Suppress("DEPRECATION")
        val uri: Uri = intent.getParcelableExtra(Intent.EXTRA_STREAM) ?: return
        val mime = intent.type ?: ""
        when {
            mime.startsWith("image/") -> viewModel.applyScreenshot(uri, fromShare = true)
            mime.startsWith("video/") -> viewModel.applyVideoFile(uri, fromShare = true)
            mime == "application/x-bittorrent" || uri.toString().endsWith(".torrent") ->
                viewModel.applyTorrent(uri, fromShare = true)
        }
    }
}

// screen transitions: slideIn+fadeIn 300ms EaseOutCubic / slideOut+fadeOut 250ms EaseInCubic
private val easeOutCubic = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)
private val easeInCubic = CubicBezierEasing(0.32f, 0f, 0.67f, 0f)

@Composable
fun SubFlowNavHost(viewModel: SearchViewModel) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val enterMs = DeviceProfiler.animMs(300)
    val exitMs = DeviceProfiler.animMs(250)

    // share jumps straight to input without clearing the form, then normalize the
    // back stack so it doesn't land on top of onboarding on first launch
    val pendingShareNav by viewModel.pendingShareNav.collectAsState()
    LaunchedEffect(pendingShareNav) {
        if (pendingShareNav) {
            viewModel.consumeShareNav()
            Onboarding.markDone(context)
            navController.navigate("home") {
                popUpTo(0) { inclusive = true }
            }
            navController.navigate("input") { launchSingleTop = true }
        }
    }

    // history tap resolves its target off the main thread, then navigates here
    val historyNav by viewModel.historyNav.collectAsState()
    LaunchedEffect(historyNav) {
        historyNav?.let { route ->
            viewModel.consumeHistoryNav()
            navController.navigate(route) { popUpTo("home") }
        }
    }

    // offline searches get queued, tell the user once
    val justQueued by viewModel.justQueued.collectAsState()
    val queuedMsg = stringResource(R.string.queued_offline)
    LaunchedEffect(justQueued) {
        if (justQueued) {
            viewModel.consumeJustQueued()
            Toast.makeText(context, queuedMsg, Toast.LENGTH_LONG).show()
        }
    }

    // widget / launcher-shortcut entry points
    val externalNav by viewModel.externalNav.collectAsState()
    LaunchedEffect(externalNav) {
        externalNav?.let { route ->
            viewModel.consumeExternalNav()
            if (route == "input") viewModel.resetForm()
            navController.navigate(route) { popUpTo("home") }
        }
    }

    val startDest = remember {
        if (Onboarding.isDone(context)) "home" else "onboarding"
    }

    NavHost(
        navController = navController,
        startDestination = startDest,
        enterTransition = {
            slideInHorizontally(tween(enterMs, easing = easeOutCubic)) { it / 3 } +
                fadeIn(tween(enterMs, easing = easeOutCubic))
        },
        exitTransition = {
            slideOutHorizontally(tween(exitMs, easing = easeInCubic)) { -it / 3 } +
                fadeOut(tween(exitMs, easing = easeInCubic))
        },
        popEnterTransition = {
            slideInHorizontally(tween(enterMs, easing = easeOutCubic)) { -it / 3 } +
                fadeIn(tween(enterMs, easing = easeOutCubic))
        },
        popExitTransition = {
            slideOutHorizontally(tween(exitMs, easing = easeInCubic)) { it / 3 } +
                fadeOut(tween(exitMs, easing = easeInCubic))
        }
    ) {
        composable("onboarding") {
            OnboardingScreen(
                onFinish = {
                    navController.navigate("home") {
                        popUpTo("onboarding") { inclusive = true }
                    }
                }
            )
        }
        composable("home") {
            HomeScreen(
                viewModel = viewModel,
                onNewSearch = {
                    viewModel.resetForm()
                    navController.navigate("input")
                },
                onRetryLast = {
                    if (viewModel.retryLast().opensProgress) navController.navigate("progress")
                },
                onSettings = { navController.navigate("settings") },
                onOpenHistory = { entry -> viewModel.openHistory(entry) },
                onFavorites = { navController.navigate("favorites") },
                onContinue = { navController.navigate("progress") },
                onRunQueue = {
                    if (viewModel.runQueue().opensProgress) navController.navigate("progress") { popUpTo("home") }
                }
            )
        }
        composable("favorites") {
            FavoritesScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onSearchStarted = { navController.navigate("progress") { popUpTo("home") } }
            )
        }
        composable("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onStats = { navController.navigate("stats") }
            )
        }
        composable("stats") {
            StatsScreen(onBack = { navController.popBackStack() })
        }
        composable("input") {
            ReleaseInputScreen(
                viewModel = viewModel,
                onStart = {
                    when (viewModel.startPipeline()) {
                        // a refusal leaves the user on the form, where the reason is
                        // already on screen; only a run that began has a progress screen
                        SearchStart.STARTED -> navController.navigate("progress")
                        SearchStart.QUEUED_OFFLINE -> navController.popBackStack("home", inclusive = false)
                        SearchStart.REFUSED_BUSY, SearchStart.NOTHING_TO_RUN -> Unit
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable("progress") {
            ProgressScreen(
                viewModel = viewModel,
                onDone = {
                    navController.navigate("result") {
                        popUpTo("home")
                    }
                },
                onCancel = {
                    viewModel.cancelPipeline()
                    navController.popBackStack("home", inclusive = false)
                }
            )
        }
        composable("result") {
            ResultScreen(
                viewModel = viewModel,
                onSearchAgain = {
                    // keep the form but drop the stale video, so a re-typed search can't play the wrong one
                    viewModel.clearPickedVideo()
                    navController.navigate("input") {
                        popUpTo("home")
                    }
                },
                onHome = { navController.popBackStack("home", inclusive = false) },
                onRetryFailed = {
                    if (viewModel.retryFailed()) {
                        navController.navigate("progress") { popUpTo("home") }
                    }
                }
            )
        }
    }
}
