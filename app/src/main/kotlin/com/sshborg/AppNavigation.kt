package com.sshborg

import android.app.Activity
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import com.sshborg.R
import com.sshborg.data.AppPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sshborg.service.SessionManager
import com.sshborg.service.SshForegroundService
import com.sshborg.ui.hosts.AddEditHostScreen
import com.sshborg.ui.hosts.HostsScreen
import com.sshborg.ui.keys.KeysScreen
import com.sshborg.ui.extrabar.ExtraBarEditorScreen
import com.sshborg.ui.extrabar.ExtraBarEditorViewModel
import com.sshborg.ui.extrabar.ExtraBarsScreen
import com.sshborg.ui.settings.SettingsScreen
import com.sshborg.ui.sftp.SftpScreen
import com.sshborg.ui.terminal.TerminalScreen

sealed class Screen(val route: String) {
    object Hosts : Screen("hosts")
    object AddEditHost : Screen("hosts/{hostId}?ec2Id={ec2Id}&ec2Name={ec2Name}&ec2Host={ec2Host}") {
        fun routeFor(id: Long) = "hosts/$id"
        /** A new host, prefilled from Booter's request and linked to its instance. */
        fun routeFor(launch: Ec2Launch) = "hosts/$NEW_ID?ec2Id=${Uri.encode(launch.instanceId)}" +
            "&ec2Name=${Uri.encode(launch.name)}&ec2Host=${Uri.encode(launch.host)}"
        const val NEW_ID = -1L
    }
    object Terminal : Screen("terminal/{sessionId}") {
        fun routeFor(sessionId: String) = "terminal/$sessionId"
    }
    object Sftp : Screen("sftp/{sessionId}") {
        fun routeFor(sessionId: String) = "sftp/$sessionId"
    }
    object Keys : Screen("keys")
    object Settings : Screen("settings")
    object ExtraBars : Screen("extrabars")
    object ExtraBarEditor : Screen("extrabars/{barId}") {
        fun routeFor(id: String) = "extrabars/$id"
    }
}

/**
 * [ec2Launch] is a request from Booter to open (MainActivity holds it back while the app is
 * locked); [onEc2LaunchTaken] tells it the request is being handled, so it runs only once.
 */
@Composable
fun AppNavigation(
    ec2Launch: Ec2Launch? = null,
    onEc2LaunchTaken: () -> Unit = {},
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val app = context.applicationContext as SshBorgApp
    val sessionManager = app.sessionManager

    // Privacy policy — must be accepted on first launch
    var showPrivacyDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(Unit) {
        if (!app.appPreferences.privacyPolicyAccepted.first()) showPrivacyDialog = true
    }
    if (showPrivacyDialog) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.privacy_policy_dialog_title)) },
            text  = {
                Column {
                    Text(stringResource(R.string.privacy_policy_dialog_body))
                    TextButton(onClick = {
                        uriHandler.openUri("https://sshborg.com/privacy_policy.html")
                    }) { Text(stringResource(R.string.action_read_policy)) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showPrivacyDialog = false
                    scope.launch { app.appPreferences.setPrivacyPolicyAccepted() }
                }) { Text(stringResource(R.string.action_accept)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    (context as Activity).finishAffinity()
                }) { Text(stringResource(R.string.action_reject)) }
            },
        )
    }

    // One-time root warning
    val isRooted = remember { RootDetector.isRooted() }
    var showRootWarning by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (isRooted && !app.appPreferences.rootWarningAcknowledged.first()) {
            showRootWarning = true
        }
    }
    if (showRootWarning) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.root_warning_title)) },
            text  = { Text(stringResource(R.string.root_warning_body)) },
            confirmButton = {
                OutlinedButton(onClick = {
                    showRootWarning = false
                    scope.launch { app.appPreferences.setRootWarningAcknowledged() }
                }) { Text(stringResource(R.string.action_i_understand)) }
            },
        )
    }

    // Delayed security reminder (shown if the app lock or keystore encryption is not enabled).
    // Same dialog on every device — only the wording differs on a TV, where the remote-access
    // risk is the point. Key on showPrivacyDialog so it waits until privacy is accepted.
    val isTv = remember { isTelevision(context) }
    var showSecurityReminder by remember { mutableStateOf(false) }
    LaunchedEffect(showPrivacyDialog) {
        if (showPrivacyDialog) return@LaunchedEffect
        delay(1500L)
        val dismissed  = app.appPreferences.securityReminderDismissed.first()
        if (!dismissed) {
            val locked   = app.appPreferences.lockMode.first() != AppPreferences.LOCK_NONE
            val keystore = app.appPreferences.keystoreEncryption.first()
            if (!locked || !keystore) showSecurityReminder = true
        }
    }
    if (showSecurityReminder) {
        AlertDialog(
            onDismissRequest = { showSecurityReminder = false },
            title = { Text(stringResource(if (isTv) R.string.tv_lock_nudge_title else R.string.security_reminder_title)) },
            text  = { Text(stringResource(if (isTv) R.string.tv_lock_nudge_body else R.string.security_reminder_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showSecurityReminder = false
                    scope.launch { app.appPreferences.setSecurityReminderDismissed() }
                }) { Text(stringResource(R.string.action_dont_show_again)) }
            },
            dismissButton = {
                TextButton(onClick = { showSecurityReminder = false }) {
                    Text(stringResource(R.string.action_remind_later))
                }
            },
        )
    }

    fun openTerminal(hostId: Long, hostLabel: String) {
        val id = sessionManager.create(hostId, hostLabel, SessionManager.SessionType.Shell)
        SshForegroundService.start(context)
        navController.navigate(Screen.Terminal.routeFor(id))
    }

    // Booter: a saved instance goes straight to its terminal (the open one, if there is one),
    // an unknown one to the host editor, prefilled.
    LaunchedEffect(ec2Launch) {
        val launch = ec2Launch ?: return@LaunchedEffect
        onEc2LaunchTaken()
        // In the outer scope: taking the request changes this effect's key, which cancels it.
        scope.launch {
            when (val target = launch.resolve(app.db.hostDao())) {
                is Ec2Launch.Target.Saved -> {
                    val host = target.host
                    val open = sessionManager.sessions.value.lastOrNull {
                        it.hostId == host.id && it.type == SessionManager.SessionType.Shell &&
                            (it.status == SessionManager.Status.Connected ||
                                it.status == SessionManager.Status.Connecting)
                    }
                    if (open != null) navController.navigate(Screen.Terminal.routeFor(open.id))
                    else openTerminal(host.id, host.label)
                }
                is Ec2Launch.Target.New ->
                    navController.navigate(Screen.AddEditHost.routeFor(target.launch))
            }
        }
    }

    NavHost(navController = navController, startDestination = Screen.Hosts.route) {

        composable(Screen.Hosts.route) {
            val sessions by sessionManager.sessions.collectAsState()

            HostsScreen(
                sessions = sessions,
                onNewTerminal = { hostId, hostLabel -> openTerminal(hostId, hostLabel) },
                onResumeTerminal = { sessionId ->
                    navController.navigate(Screen.Terminal.routeFor(sessionId))
                },
                onNewSftp = { hostId, hostLabel ->
                    val id = sessionManager.create(hostId, hostLabel, SessionManager.SessionType.Sftp)
                    SshForegroundService.start(context)
                    navController.navigate(Screen.Sftp.routeFor(id))
                },
                onResumeSftp = { sessionId ->
                    navController.navigate(Screen.Sftp.routeFor(sessionId))
                },
                onAddHost  = { navController.navigate(Screen.AddEditHost.routeFor(Screen.AddEditHost.NEW_ID)) },
                onEditHost = { hostId -> navController.navigate(Screen.AddEditHost.routeFor(hostId)) },
                onKeysClick = { navController.navigate(Screen.Keys.route) },
                onSettingsClick = { navController.navigate(Screen.Settings.route) },
            )
        }

        composable(
            Screen.AddEditHost.route,
            arguments = listOf(
                navArgument("hostId") { type = NavType.LongType },
                navArgument("ec2Id") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("ec2Name") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("ec2Host") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { backEntry ->
            val args = backEntry.arguments
            val hostId = args?.getLong("hostId") ?: Screen.AddEditHost.NEW_ID
            val ec2Prefill = args?.getString("ec2Id")?.let { ec2Id ->
                Ec2Launch(ec2Id, args.getString("ec2Name") ?: ec2Id, args.getString("ec2Host").orEmpty())
            }
            AddEditHostScreen(
                hostId = hostId,
                ec2Prefill = ec2Prefill,
                onSaved = { savedId, savedLabel ->
                    navController.popBackStack()
                    // Booter asked to open this instance: connect now that it has a host.
                    if (ec2Prefill != null) openTerminal(savedId, savedLabel)
                },
                onBack  = { navController.popBackStack() },
            )
        }

        composable(
            Screen.Terminal.route,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { backEntry ->
            val sessionId = backEntry.arguments?.getString("sessionId") ?: return@composable
            val sessions by sessionManager.sessions.collectAsState()

            TerminalScreen(
                sessionId = sessionId,
                sessions  = sessions,
                onBack    = { navController.popBackStack() },
                onSwitchSession = { newId ->
                    // Keep the soft keyboard up across the tab switch: the
                    // outgoing screen's onDispose checks this flag and skips
                    // hiding the IME (see TerminalScreen).
                    sessionManager.switchingTab = true
                    navController.navigate(Screen.Terminal.routeFor(newId)) {
                        popUpTo(Screen.Terminal.routeFor(sessionId)) { inclusive = true }
                    }
                },
            )
        }

        composable(
            Screen.Sftp.route,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { backEntry ->
            val sessionId = backEntry.arguments?.getString("sessionId") ?: return@composable
            SftpScreen(
                sessionId = sessionId,
                onBack    = { navController.popBackStack() },
            )
        }

        composable(Screen.Keys.route) {
            KeysScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onExtraBars = { navController.navigate(Screen.ExtraBars.route) },
            )
        }

        composable(Screen.ExtraBars.route) {
            ExtraBarsScreen(
                onBack = { navController.popBackStack() },
                onEdit = { id -> navController.navigate(Screen.ExtraBarEditor.routeFor(id)) },
            )
        }

        composable(
            Screen.ExtraBarEditor.route,
            arguments = listOf(navArgument("barId") { type = NavType.StringType }),
        ) { entry ->
            ExtraBarEditorScreen(
                barId = entry.arguments?.getString("barId") ?: ExtraBarEditorViewModel.NEW_ID,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
