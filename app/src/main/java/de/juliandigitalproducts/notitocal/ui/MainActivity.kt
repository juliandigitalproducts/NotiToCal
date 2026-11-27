// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.juliandigitalproducts.notitocal.NotiToCalApp
import de.juliandigitalproducts.notitocal.R
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.notify.Notifier
import de.juliandigitalproducts.notitocal.ui.theme.NotiToCalTheme
import kotlinx.coroutines.launch
import de.juliandigitalproducts.notitocal.model.runSafely

class MainActivity : ComponentActivity() {
  private val entryRequest = mutableStateOf<Long?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    // Kept until the settings are read (they load in the background, see SettingsRepository).
    installSplashScreen().setKeepOnScreenCondition { !(application as de.juliandigitalproducts.notitocal.NotiToCalApp).settings.isLoaded }
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    readIntent(intent)
    setContent {
      NotiToCalTheme {
        // Surface provides background and content colours for screens without a Scaffold (wizard).
        Surface(Modifier.fillMaxSize().imePadding(), color = MaterialTheme.colorScheme.background) { AppRoot(entryRequest) }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    readIntent(intent)
  }

  private fun readIntent(intent: Intent?) {
    val id = intent?.getLongExtra(Notifier.EXTRA_ENTRY, -1)?.takeIf { it >= 0 }
      ?: intent?.getLongExtra("entry_id", -1)?.takeIf { it > 0 }
    id?.let { entryRequest.value = it }
  }
}

/** Shared helpers for screens. */
object Ui {
  val app: NotiToCalApp get() = NotiToCalApp.instance

  fun update(transform: (AppSettings) -> AppSettings) {
    app.scope.launch { app.settings.update(transform) }
  }

  /** Permission to read screenshots: READ_MEDIA_IMAGES from Android 13, READ_EXTERNAL_STORAGE before. */
  val imagePermission: String
    get() = if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

  /** Runtime notification permission only exists from Android 13. */
  fun notificationsGranted(context: Context) = de.juliandigitalproducts.notitocal.notify.Notifier.canPost(context)

  /** Number of monitored apps that are actually installed (the defaults list common messengers). */
  fun installedMonitoredCount(context: Context, packages: Set<String>): Int = packages.count { pkg ->
    runSafely { context.packageManager.getApplicationInfo(pkg, 0) }.isSuccess
  }

  fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

  fun openNotificationAccess(context: Context) {
    runSafely { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
  }

  fun openUrl(context: Context, url: String) {
    runSafely { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
  }
}

/** Increments whenever the activity resumes, so screens can re-check permissions. */
@Composable
fun rememberResumeTick(): Int {
  var tick by remember { mutableIntStateOf(0) }
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
  return tick
}

fun NavController.safePop(from: NavBackStackEntry? = null) {
  if (from != null && from.lifecycle.currentState != Lifecycle.State.RESUMED) return
  if (previousBackStackEntry == null) return
  if (currentBackStackEntry?.lifecycle?.currentState != Lifecycle.State.RESUMED) return
  popBackStack()
}

private data class BottomNavItem(
  val route: String,
  val labelRes: Int,
  val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

private val topLevelNavItems = listOf(
  BottomNavItem("home", R.string.nav_home, Icons.Filled.Home),
  BottomNavItem("settings/duplicates", R.string.nav_duplicates, Icons.Filled.CallMerge),
  BottomNavItem("settings/sources", R.string.nav_sources, Icons.Filled.Inbox),
  BottomNavItem("settings/targets", R.string.nav_targets, Icons.Filled.CalendarMonth),
  BottomNavItem("settings", R.string.nav_settings, Icons.Filled.Tune),
)

/**
 * Which bottom tab "owns" the current screen. A screen opened from inside a tab (e.g. Sources opened from
 * Settings) stays under that tab, so tapping the highlighted tab again returns to its overview.
 */
internal object TabNav {
  const val HOME = "home"
  private const val WIZARD = "wizard"

  fun owner(currentRoute: String?, previousRoute: String?, currentOwner: String, tabs: Collection<String>): String = when {
    currentRoute == HOME -> HOME
    currentRoute in tabs && (previousRoute == null || previousRoute == HOME) -> currentRoute!!
    else -> currentOwner
  }

  fun showBar(currentRoute: String?): Boolean = currentRoute != null && currentRoute != WIZARD
}

/** Tapping a tab always opens its overview and drops whatever sub-screens were open (no restored sub-path). */
private fun NavController.openTab(route: String) {
  if (route == TabNav.HOME) {
    popBackStack(TabNav.HOME, inclusive = false)
    return
  }
  navigate(route) {
    popUpTo(TabNav.HOME)
    launchSingleTop = true
  }
}

@Composable
private fun AppRoot(entryRequest: MutableState<Long?>) {
  val nav = rememberNavController()
  val start = remember { if (Ui.app.settings.current.onboardingDone) TabNav.HOME else "wizard" }
  val backStackEntry by nav.currentBackStackEntryAsState()
  val currentRoute = backStackEntry?.destination?.route
  val tabRoutes = remember { topLevelNavItems.map { it.route } }
  var activeTab by rememberSaveable { mutableStateOf(TabNav.HOME) }
  LaunchedEffect(backStackEntry) {
    activeTab = TabNav.owner(currentRoute, nav.previousBackStackEntry?.destination?.route, activeTab, tabRoutes)
  }

  LaunchedEffect(entryRequest.value) {
    entryRequest.value?.let { id ->
      if (Ui.app.settings.current.onboardingDone) nav.navigate("entry/$id")
      entryRequest.value = null
    }
  }

  Scaffold(
    bottomBar = {
      if (TabNav.showBar(currentRoute)) {
        NavigationBar {
          topLevelNavItems.forEach { item ->
            NavigationBarItem(
              selected = activeTab == item.route,
              onClick = { if (currentRoute != item.route || activeTab != item.route) nav.openTab(item.route) },
              icon = { Icon(item.icon, contentDescription = stringResource(item.labelRes)) },
              label = { Text(stringResource(item.labelRes), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
          }
        }
      }
    },
  ) { paddingValues ->
    NavHost(
      navController = nav,
      startDestination = start,
      // Consume the bar's insets so the screens' own Scaffolds don't add the system navigation bar a second time.
      modifier = Modifier
        .padding(bottom = paddingValues.calculateBottomPadding())
        .consumeWindowInsets(PaddingValues(bottom = paddingValues.calculateBottomPadding())),
    ) {
      composable("wizard") {
        WizardScreen(
          onDone = {
            // Opened from the wizard overview: go back to the existing home instead of stacking a second one.
            if (!nav.popBackStack("home", inclusive = false)) {
              nav.navigate("home") { popUpTo("wizard") { inclusive = true } }
            }
          },
          onOpen = { nav.navigate(it) },
        )
      }
      composable("home") { HomeScreen(onOpen = { nav.navigate(it) }) }
      composable("entry/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { back ->
        EntryScreen(back.arguments!!.getLong("id"), onBack = { nav.safePop(back) })
      }
      composable("settings") { back -> SettingsScreen(onBack = { nav.safePop(back) }, onOpen = { nav.navigate(it) }) }
      composable("settings/engine") { back -> EngineScreen(onBack = { nav.safePop(back) }, onOpen = { nav.navigate(it) }) }
      composable("settings/sources") { back -> SourcesScreen(onBack = { nav.safePop(back) }, onOpen = { nav.navigate(it) }) }
      composable("settings/sources/{key}") { back ->
        SourceDetailScreen(back.arguments?.getString("key").orEmpty(), onBack = { nav.safePop(back) }, onOpen = { nav.navigate(it) })
      }
      composable("settings/strictness") { back -> StrictnessScreen(onBack = { nav.safePop(back) }) }
      composable("settings/targets") { back -> TargetsScreen(onBack = { nav.safePop(back) }, onOpen = { nav.navigate(it) }) }
      composable("settings/targets/{key}") { back ->
        TargetDetailScreen(back.arguments?.getString("key").orEmpty(), onBack = { nav.safePop(back) }, onOpen = { nav.navigate(it) })
      }
      composable("settings/battery") { back -> BatteryScreen(onBack = { nav.safePop(back) }, onOpen = { nav.navigate(it) }) }
      composable("routing") { back -> RoutingScreen(onBack = { nav.safePop(back) }, onOpen = { nav.navigate(it) }) }
      composable("routing/{id}") { back -> RoutingRuleScreen(back.arguments?.getString("id").orEmpty(), onBack = { nav.safePop(back) }) }
      composable("settings/diagnose") { back -> DiagnoseScreen(onBack = { nav.safePop(back) }, onOpen = { nav.navigate(it) }, initialTab = 0) }
      composable("settings/bugreport") { back -> DiagnoseScreen(onBack = { nav.safePop(back) }, onOpen = { nav.navigate(it) }, initialTab = 1) }
      composable("settings/duplicates") { back -> DuplicatesScreen(onBack = { nav.safePop(back) }) }
      composable("settings/locations") { back -> LocationEnrichmentScreen(onBack = { nav.safePop(back) }) }
      composable("settings/folders") { back -> FoldersScreen(onBack = { nav.safePop(back) }) }
      composable("settings/templates") { back -> TemplatesScreen(onBack = { nav.safePop(back) }) }
      composable("settings/how_it_works") { back -> HowItWorksScreen(onBack = { nav.safePop(back) }) }
      composable("settings/privacy") { back -> PrivacyScreen(onBack = { nav.safePop(back) }, onOpenGuide = { nav.navigate("settings/how_it_works") },
        onOpenLicenses = { nav.navigate("settings/licenses") }, onOpenGoogleNotices = { nav.navigate("settings/licenses_google") }, onRestartWizard = {
        Ui.update { it.copy(onboardingDone = false) }
        nav.navigate("wizard") { popUpTo("home") { inclusive = true } }
      }) }
      composable("settings/licenses") { back -> LicensesScreen(onBack = { nav.safePop(back) }) }
      composable("settings/licenses_google") { back -> GoogleNoticesScreen(onBack = { nav.safePop(back) }) }
      composable("settings/backup") { back -> DiagnoseScreen(onBack = { nav.safePop(back) }, onOpen = { nav.navigate(it) }, initialTab = 2) }
      composable("apps") { back -> AppSelectorScreen(onBack = { nav.safePop(back) }, onOpen = { nav.navigate(it) }) }
      composable("settings/screenshot_apps") { back -> ScreenshotAppsScreen(onBack = { nav.safePop(back) }) }
      composable("models") { back -> ModelScreen(onBack = { nav.safePop(back) }) }
      composable("statistics") { back -> StatisticsScreen(onBack = { nav.safePop(back) }) }
      composable("history_full") { back -> FullHistoryScreen(onBack = { nav.safePop(back) }, onOpen = { nav.navigate(it) }) }
      composable("wizards") { back -> WizardsOverviewScreen(onBack = { nav.safePop(back) }, onOpen = { nav.navigate(it) }) }
    }
  }
}
