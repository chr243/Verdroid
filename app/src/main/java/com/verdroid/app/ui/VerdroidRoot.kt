package com.verdroid.app.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.verdroid.app.domain.repository.NotificationRepository
import com.verdroid.app.ui.chat.ChatDetailScreen
import com.verdroid.app.ui.device.DeviceControlScreen
import com.verdroid.app.ui.device.KeepAliveScreen
import com.verdroid.app.ui.chats.ChatsScreen
import com.verdroid.app.ui.menu.*
import com.verdroid.app.ui.notifications.NotificationsScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

object Routes {
    const val CHATS = "chats"
    const val NOTIFICATIONS = "notifications"
    const val SETTINGS = "settings"
    const val MCP = "settings/mcp"
    const val CHAT = "chat/{taskId}"
    const val KEYS = "settings/keys"
    const val MODELS = "settings/models"
    const val LIMITS = "settings/limits"
    const val SHIZUKU = "shizuku"
    const val CHAT_HEADS = "settings/chatheads"
    const val KEEP_ALIVE = "settings/keepalive"
    const val TOOLS = "settings/tools"
    const val SKILLS = "settings/skills"
    const val AGENTS_MD = "settings/agents-md"
    const val PERMISSIONS = "settings/permissions"
    const val THEME = "settings/theme"
    const val ARCHIVE = "settings/archive"
    const val SELF_TEST = "settings/selftest"
    const val PROVIDER = "settings/provider"
    const val DEBUG_LOG = "settings/debuglog"
    const val MEMORY = "settings/memory?chat={chat}"
    fun memory(chatId: Long? = null) = "settings/memory?chat=${chatId ?: -1}"
    fun chat(taskId: Long) = "chat/$taskId"
}

/**
 * Material motion (v1.0.37):
 * - Shared axis X for Settings and its sub-pages: ~30dp slide + fade-through style fade, 300ms emphasized.
 * - Chat list <-> chat: container transform (sharedBounds in [chatContainer]); the screens themselves only fade.
 */
private object NavMotion {
    private const val FADE_OUT_MS = 90 // ~35% of the duration: outgoing fades fast, incoming fades in after
    private val SLIDE = 30.dp

    fun enter(px: Int): EnterTransition =
        fadeIn(tween(MOTION_MS - FADE_OUT_MS, delayMillis = FADE_OUT_MS, easing = LinearOutSlowInEasing)) +
            slideInHorizontally(tween(MOTION_MS, easing = EmphasizedEasing)) { px }
    fun exit(px: Int): ExitTransition =
        fadeOut(tween(FADE_OUT_MS, easing = FastOutLinearInEasing)) +
            slideOutHorizontally(tween(MOTION_MS, easing = EmphasizedEasing)) { -px }
    fun popEnter(px: Int): EnterTransition = enter(-px)
    fun popExit(px: Int): ExitTransition = exit(-px)
    fun slidePx(d: Density) = with(d) { SLIDE.roundToPx() }

    // Chat swaps: the container transform carries the motion; this fade+scale is also the visible fallback when the
    // shared bounds don't match (stories row, Archive, Notifications, new chats).
    val fadeEnter: EnterTransition = fadeIn(tween(CONTAINER_MS, easing = LinearOutSlowInEasing)) +
        scaleIn(tween(CONTAINER_MS, easing = EmphasizedEasing), initialScale = 0.92f)
    val fadeExit: ExitTransition = fadeOut(tween(CONTAINER_MS, easing = FastOutLinearInEasing)) +
        scaleOut(tween(CONTAINER_MS, easing = EmphasizedEasing), targetScale = 0.96f)
}

@HiltViewModel
class RootViewModel @Inject constructor(
    notifications: NotificationRepository,
    val updater: com.verdroid.app.data.update.AppUpdater,
) : ViewModel() {
    val unread = notifications.observeUnreadCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun VerdroidRoot(
    openTaskId: Long? = null,
    onOpenTaskConsumed: () -> Unit = {},
    vm: RootViewModel = hiltViewModel(),
) {
    val nav = rememberNavController()
    val unread by vm.unread.collectAsStateWithLifecycle()

    LaunchedEffect(openTaskId) {
        if (openTaskId != null && openTaskId != 0L) {
            nav.navigate(Routes.chat(openTaskId)) { launchSingleTop = true }
            onOpenTaskConsumed()
        }
    }

    // No bottom bar (v0.9.18): the chat list is home; Settings opens from its gear.
    StartupUpdatePrompt(vm.updater)
    val px = NavMotion.slidePx(LocalDensity.current)
    LaunchedEffect(Unit) {
        // Compose honours the system animator scale: 0 (HyperOS "remove animations", dev options, some battery
        // savers) turns every transition, including the container transform, into a hard cut.
        if (!android.animation.ValueAnimator.areAnimatorsEnabled())
            android.util.Log.w("VerdroidMotion", "System animator duration scale is 0: all animations disabled")
    }
    fun AnimatedContentTransitionScope<NavBackStackEntry>.chatSwap() =
        initialState.destination.route == Routes.CHAT || targetState.destination.route == Routes.CHAT
    SharedTransitionLayout {
      CompositionLocalProvider(LocalSharedTransitionScope provides this) {
        NavHost(
            nav,
            startDestination = Routes.CHATS,
            enterTransition = { if (chatSwap()) NavMotion.fadeEnter else NavMotion.enter(px) },
            exitTransition = { if (chatSwap()) NavMotion.fadeExit else NavMotion.exit(px) },
            popEnterTransition = { if (chatSwap()) NavMotion.fadeEnter else NavMotion.popEnter(px) },
            popExitTransition = { if (chatSwap()) NavMotion.fadeExit else NavMotion.popExit(px) },
        ) {
            composable(Routes.CHATS) {
              CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                ChatsScreen(
                    onOpenChat = { nav.navigate(Routes.chat(it)) },
                    onNewChat = { nav.navigate(Routes.chat(0)) },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                    onOpenKeys = { nav.navigate(Routes.KEYS) },
                    onOpenModels = { nav.navigate(Routes.MODELS) },
                )
              }
            }
            composable(Routes.NOTIFICATIONS) {
                NotificationsScreen(onOpenChat = { nav.navigate(Routes.chat(it)) }, onBack = { nav.popBackStack() })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onNavigate = { nav.navigate(it) }, unreadNotifications = unread,
                    onBack = { if (!nav.popBackStack(Routes.CHATS, inclusive = false)) nav.navigate(Routes.CHATS) })
            }
            composable(Routes.CHAT, arguments = listOf(navArgument("taskId") { type = NavType.LongType })) {
                // VisibleChat is published by ChatDetailScreen from the ViewModel's real id: a new chat's route stays
                // "chat/0" after the first send creates the task.
              CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                ChatDetailScreen(onBack = { nav.popBackStack() },
                    onChatMemory = { id -> nav.navigate(Routes.memory(id)) })
              }
            }
            composable(Routes.KEYS) { com.verdroid.app.ui.provider.ProviderScreen(onBack = { nav.popBackStack() }, initial = "openrouter", onOpenModels = { nav.navigate(Routes.MODELS) }) }
            composable(Routes.MODELS) { ModelPriorityScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.LIMITS) { RateLimitSettingsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.SHIZUKU) { DeviceControlScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.KEEP_ALIVE) { KeepAliveScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.MCP) { com.verdroid.app.ui.tools.McpServersScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.TOOLS) { com.verdroid.app.ui.tools.ToolsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.PERMISSIONS) {
                com.verdroid.app.ui.permissions.PermissionsScreen(onBack = { nav.popBackStack() }, onTermuxSetup = { nav.navigate(Routes.TOOLS) })
            }
            composable(Routes.AGENTS_MD) { com.verdroid.app.ui.instructions.AgentsMdScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.SKILLS) { com.verdroid.app.ui.skills.SkillsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.MEMORY, arguments = listOf(navArgument("chat") { type = NavType.LongType; defaultValue = -1L })) {
                com.verdroid.app.ui.memory.MemoryScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.ARCHIVE) {
                com.verdroid.app.ui.archive.ArchiveScreen(onBack = { nav.popBackStack() }, onOpenChat = { nav.navigate(Routes.chat(it)) })
            }
            composable(Routes.THEME) { com.verdroid.app.ui.theme.ThemeScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.PROVIDER) { com.verdroid.app.ui.provider.ProviderScreen(onBack = { nav.popBackStack() }, onOpenModels = { nav.navigate(Routes.MODELS) }) }
            composable(Routes.DEBUG_LOG) { com.verdroid.app.ui.debug.DebugLogScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.SELF_TEST) { com.verdroid.app.ui.selftest.SelfTestScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.CHAT_HEADS) { ChatHeadSettingsScreen(onBack = { nav.popBackStack() }) }
        }
      }
    }
}
