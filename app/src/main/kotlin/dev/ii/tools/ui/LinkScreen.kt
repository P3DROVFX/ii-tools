@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.ii.tools.ui

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.calls.CallWatcher
import dev.ii.tools.clip.ClipSync
import dev.ii.tools.clip.ClipWatchService
import dev.ii.tools.files.FileServer
import dev.ii.tools.gallery.Gallery
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.LinkProblem
import dev.ii.tools.link.LinkStatus
import dev.ii.tools.link.PairedPc
import dev.ii.tools.link.Wake
import dev.ii.tools.live.LiveHub
import dev.ii.tools.media.PcMedia
import dev.ii.tools.media.PhoneMedia
import dev.ii.tools.notify.NotifMirror
import dev.ii.tools.pclive.DndSync
import dev.ii.tools.pclive.PcLive
import dev.ii.tools.phonedata.PhoneData
import dev.ii.tools.shots.ShotSync
import dev.ii.tools.tiles.FavoritePreset
import dev.ii.tools.ui.theme.Expressive
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** What the settings show, re-read whenever the app comes back (permissions change in Android's own settings). */
private data class SettingsState(
    val listener: Boolean,
    val post: Boolean,
    val phone: Boolean,
    val contacts: Boolean,
    val photos: Boolean,
    val gallery: Boolean,
    val galleryImages: Boolean,
    val files: Boolean,
    val restricted: Boolean,
    val copyService: Boolean,
    val battery: Boolean,
    val kdeConnect: Boolean,
)

/**
 * Settings, and where pairing lives: the PC (wake, retry, forget), the connection, every
 * permission the app asks for, and every switch of every feature in one place. The
 * feature screens keep theirs too; both write the same preference.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpen: (Dest) -> Unit) {
    val ctx = LocalContext.current
    val status by LinkHub.status.collectAsStateWithLifecycle()
    val pc by LinkHub.pc.collectAsStateWithLifecycle()
    val relayed by LiveHub.relayed.collectAsStateWithLifecycle()
    val favorite by FavoritePreset.name.collectAsStateWithLifecycle()
    val scanner = rememberPairScanner()
    val network = rememberNetworkPermission()

    fun read() = readDeviceStatus(ctx).let { d ->
        SettingsState(
            listener = d.listenerGranted,
            post = d.postGranted,
            phone = CallWatcher.hasPermissions(ctx),
            contacts = PhoneData.canReadContacts(ctx),
            photos = ShotSync.canWatch(ctx),
            gallery = Gallery.hasPermission(ctx),
            galleryImages = Gallery.canReadImages(ctx),
            files = FileServer.hasPermission(),
            restricted = ClipWatchService.restricted(ctx),
            copyService = ClipWatchService.isEnabled(ctx),
            battery = LinkHub.batteryUnrestricted(),
            kdeConnect = d.kdeConnectInstalled,
        )
    }
    var s by remember { mutableStateOf(read()) }
    var gallery by remember { mutableStateOf(Gallery.isEnabled(ctx)) }
    LifecycleResumeEffect(Unit) {
        s = read()
        gallery = Gallery.isEnabled(ctx)
        Gallery.onPermissionChanged(ctx)
        FavoritePreset.load(ctx)
        onPauseOrDispose { }
    }

    // The switches, each over its feature's own preference.
    var stays by remember { mutableStateOf(LinkHub.staysConnected()) }
    var notifs by remember { mutableStateOf(NotifMirror.isEnabled(ctx)) }
    var phoneMedia by remember { mutableStateOf(PhoneMedia.isEnabled(ctx)) }
    var calls by remember { mutableStateOf(CallWatcher.isEnabled(ctx)) }
    var data by remember { mutableStateOf(PhoneData.isEnabled(ctx)) }
    var shotsSend by remember { mutableStateOf(ShotSync.sends(ctx)) }
    var clipAuto by remember { mutableStateOf(ClipSync.autoSend(ctx)) }
    var browse by remember { mutableStateOf(FileServer.isEnabled(ctx)) }
    var pcLive by remember { mutableStateOf(PcLive.isEnabled(ctx)) }
    var pcMedia by remember { mutableStateOf(PcMedia.isEnabled(ctx)) }
    var shotsRecv by remember { mutableStateOf(ShotSync.receives(ctx)) }
    var clipRecv by remember { mutableStateOf(ClipSync.receives(ctx)) }
    var dnd by remember { mutableStateOf(DndSync.isEnabled(ctx)) }

    val askPhone = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        s = read()
        if (s.phone) CallWatcher.setEnabled(ctx, calls)
    }
    val askContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        s = read()
        LinkHub.announceCaps()
    }
    val askPhotos = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        s = read()
        if (ok) ShotSync.setSends(ctx, shotsSend)
    }
    val askGallery = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        s = read()
        Gallery.onPermissionChanged(ctx)
    }
    val askPost = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { s = read() }
    val listenerSettings = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }

    var confirmForget by remember { mutableStateOf(false) }
    val missing = listOf(
        s.listener, s.post, s.phone, s.contacts, s.photos, s.gallery, s.files, !s.restricted, s.copyService, s.battery, network.granted,
    ).count { !it }

    ToolScaffold(
        title = stringResource(R.string.settings_title),
        onBack = onBack,
        subtitle = if (missing == 0) stringResource(R.string.settings_all_allowed) else pluralStringResource(R.plurals.settings_missing, missing, missing),
    ) {
        val current = pc
        if (current == null) {
            item { PairContent(status, scanner, network) }
        } else {
            item { PcCard(current, status, onForget = { confirmForget = true }) }
        }

        item { GroupHeader(stringResource(R.string.settings_connection)) }
        item {
            CardGroup {
                SwitchRow(stringResource(R.string.link_stay), stays, {
                    stays = it
                    LinkHub.setStaysConnected(it)
                }, R.drawable.rounded_sync, summary = stringResource(R.string.settings_stay_body))
                PermissionRow(
                    stringResource(R.string.link_battery), stringResource(R.string.settings_battery_body), R.drawable.rounded_battery_full, s.battery,
                ) {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.fromParts("package", ctx.packageName, null)))
                }
                PermissionRow(stringResource(R.string.perm_network), stringResource(R.string.perm_network_body), R.drawable.rounded_devices, network.granted, network.request)
            }
        }

        item { GroupHeader(stringResource(R.string.settings_permissions)) }
        item {
            CardGroup {
                PermissionRow(stringResource(R.string.perm_listener), stringResource(R.string.settings_listener_body), R.drawable.rounded_notifications, s.listener, listenerSettings)
                PermissionRow(stringResource(R.string.perm_post), stringResource(R.string.settings_post_body), R.drawable.rounded_notification_add, s.post) {
                    askPost.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                PermissionRow(stringResource(R.string.perm_phone), stringResource(R.string.perm_phone_body), R.drawable.rounded_call, s.phone) { askPhone.launch(CallWatcher.PERMISSIONS) }
                PermissionRow(stringResource(R.string.perm_contacts), stringResource(R.string.perm_contacts_body), R.drawable.rounded_contacts, s.contacts) { askContacts.launch(PhoneData.CONTACTS) }
                PermissionRow(stringResource(R.string.perm_photos), stringResource(R.string.perm_photos_body), R.drawable.rounded_photo_library, s.photos) { askPhotos.launch(ShotSync.PERMISSION) }
                PermissionRow(stringResource(R.string.perm_gallery), stringResource(R.string.perm_gallery_body), R.drawable.rounded_photo_library, s.gallery) { askGallery.launch(Gallery.PERMISSIONS) }
                PermissionRow(stringResource(R.string.perm_files), stringResource(R.string.perm_files_body), R.drawable.rounded_folder_open, s.files) {
                    ctx.startActivity(FileServer.permissionIntent(ctx))
                }
                PermissionRow(stringResource(R.string.settings_restricted), stringResource(R.string.clip_step_restricted_body), R.drawable.rounded_shield, !s.restricted) {
                    ctx.startActivity(ClipWatchService.appInfoIntent(ctx))
                }
                PermissionRow(stringResource(R.string.settings_copy_service), stringResource(R.string.clip_step_service_body), R.drawable.rounded_content_paste, s.copyService) {
                    ClipWatchService.openSettings(ctx)
                }
            }
        }

        item { GroupHeader(stringResource(R.string.settings_to_pc)) }
        item {
            CardGroup {
                SwitchRow(stringResource(R.string.notifs_mirror), notifs, {
                    notifs = it
                    NotifMirror.setEnabled(ctx, it)
                }, R.drawable.rounded_notifications, summary = if (!s.listener) stringResource(R.string.settings_needs_listener) else null)
                SwitchRow(stringResource(R.string.pclink_media), phoneMedia, {
                    phoneMedia = it
                    PhoneMedia.setEnabled(ctx, it)
                }, R.drawable.rounded_music_note)
                SwitchRow(stringResource(R.string.calls_report), calls, {
                    calls = it
                    CallWatcher.setEnabled(ctx, it)
                }, R.drawable.rounded_call, enabled = s.phone, summary = if (!s.phone) stringResource(R.string.settings_needs_phone) else null)
                SwitchRow(stringResource(R.string.data_share), data, {
                    data = it
                    PhoneData.setEnabled(ctx, it)
                }, R.drawable.rounded_battery_full, summary = stringResource(R.string.settings_data_body))
                SwitchRow(stringResource(R.string.shots_send), shotsSend, {
                    shotsSend = it
                    ShotSync.setSends(ctx, it)
                }, R.drawable.rounded_screenshot, enabled = s.photos, summary = if (!s.photos) stringResource(R.string.settings_needs_photos) else null)
                SwitchRow(stringResource(R.string.gallery_share), gallery, {
                    gallery = it
                    Gallery.setEnabled(ctx, it)
                }, R.drawable.rounded_photo_library, enabled = s.galleryImages, summary = if (!s.galleryImages) stringResource(R.string.settings_needs_gallery) else null)
                SwitchRow(stringResource(R.string.clip_auto), clipAuto, {
                    clipAuto = it
                    ClipSync.setAutoSend(ctx, it)
                }, R.drawable.rounded_content_paste_go, summary = if (clipAuto && !s.copyService) stringResource(R.string.settings_needs_copy) else null)
                SwitchRow(stringResource(R.string.pclink_files), browse, {
                    browse = it
                    FileServer.setEnabled(ctx, it)
                }, R.drawable.rounded_folder_open, summary = if (browse && !s.files) stringResource(R.string.settings_needs_files) else null)
            }
        }

        item { GroupHeader(stringResource(R.string.settings_from_pc)) }
        item {
            CardGroup {
                SwitchRow(stringResource(R.string.pclink_live), pcLive, {
                    pcLive = it
                    PcLive.setEnabled(ctx, it)
                }, R.drawable.rounded_timer)
                SwitchRow(stringResource(R.string.pclink_pc_media), pcMedia, {
                    pcMedia = it
                    PcMedia.setEnabled(ctx, it)
                }, R.drawable.rounded_queue_music)
                SwitchRow(stringResource(R.string.shots_receive), shotsRecv, {
                    shotsRecv = it
                    ShotSync.setReceives(ctx, it)
                }, R.drawable.rounded_download)
                SwitchRow(stringResource(R.string.clip_receive), clipRecv, {
                    clipRecv = it
                    ClipSync.setReceives(ctx, it)
                }, R.drawable.rounded_content_paste)
                SwitchRow(stringResource(R.string.pclink_dnd), dnd, {
                    dnd = it
                    DndSync.setEnabled(ctx, it)
                }, R.drawable.rounded_do_not_disturb_on, summary = stringResource(R.string.settings_dnd_body))
            }
        }

        item { GroupHeader(stringResource(R.string.settings_more)) }
        item {
            CardGroup {
                ToolRow(
                    title = stringResource(R.string.live_title),
                    summary = if (relayed.isEmpty()) stringResource(R.string.settings_relay_rules) else pluralStringResource(R.plurals.settings_relaying, relayed.size, relayed.size),
                    icon = R.drawable.rounded_notifications_active,
                    tint = if (s.listener && s.post) Tint.PRIMARY else Tint.TERTIARY,
                    onClick = { onOpen(Dest.LIVE) },
                )
                ToolRow(
                    title = stringResource(R.string.tile_preset),
                    summary = favorite ?: stringResource(R.string.settings_preset_none),
                    icon = R.drawable.rounded_star,
                    onClick = { onOpen(Dest.REMOTE) },
                )
                ToolRow(
                    title = stringResource(R.string.kdeconnect),
                    summary = stringResource(if (s.kdeConnect) R.string.kdeconnect_installed else R.string.kdeconnect_missing_short),
                    icon = if (s.kdeConnect) R.drawable.rounded_devices else R.drawable.rounded_warning,
                    tint = if (s.kdeConnect) Tint.SECONDARY else Tint.ERROR,
                )
                ToolRow(
                    title = stringResource(R.string.tools_title),
                    icon = R.drawable.rounded_apps,
                    onClick = { onOpen(Dest.TOOLS) },
                )
            }
        }
        item { Hint(stringResource(R.string.settings_license), Modifier.fillMaxWidth().padding(top = 8.dp)) }
    }

    if (confirmForget) ForgetSheet(onDismiss = { confirmForget = false })
}

/** The paired PC: its state, its addresses, and the ways to reach it or let it go. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PcCard(pc: PairedPc, status: LinkStatus, onForget: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var woke by remember { mutableStateOf<Boolean?>(null) }
    val online = status is LinkStatus.Online
    val (bg, fg) = if (online) cs.primaryContainer to cs.onPrimaryContainer else Layer.pane to cs.onSurface
    ExpressiveCard(bg, fg) {
        Ornament(MaterialShapes.Cookie12Sided, 300.dp, fg, Alignment.TopEnd, x = 120.dp, y = (-110).dp, alpha = 0.1f)
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                StateShape(active = online, size = 60.dp, color = if (online) cs.onPrimaryContainer else Layer.field, on = MaterialShapes.Cookie12Sided) {
                    if (status is LinkStatus.Connecting) LoadingIndicator(Modifier.size(36.dp))
                    else Icon(painterResource(R.drawable.rounded_desktop_windows), null, tint = if (online) cs.primaryContainer else cs.onSurfaceVariant, modifier = Modifier.size(28.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(pc.name.ifEmpty { stringResource(R.string.link_title) }, style = Expressive.xwide(28.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            woke == true -> stringResource(R.string.wake_sent)
                            woke == false -> stringResource(R.string.wake_failed)
                            status is LinkStatus.Online -> stringResource(R.string.status_online) + " · " + status.host
                            status is LinkStatus.Offline -> problemText(status.problem)
                            else -> statusLabel(status)
                        },
                        style = Expressive.live(14.sp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.rounded_lan), stringResource(R.string.link_address), Modifier.size(18.dp))
                Text(pc.hosts.joinToString(" · ") { "$it:${pc.port}" }, style = Expressive.digitsSoft(16.sp), maxLines = 2)
            }
            if (!online) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (Wake.available(ctx)) {
                        FilledPill(stringResource(R.string.wake_pc), onClick = { scope.launch { woke = LinkHub.wake() } }, icon = R.drawable.rounded_power_settings_new, modifier = Modifier.weight(1.3f))
                    }
                    DashedPill(stringResource(R.string.retry), onClick = { LinkHub.retry() }, icon = R.drawable.rounded_refresh, modifier = Modifier.weight(1f))
                }
            }
            FilledPill(
                stringResource(R.string.forget_pc), onClick = onForget, icon = R.drawable.rounded_link_off,
                container = cs.errorContainer, content = cs.onErrorContainer, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Forgetting is the one thing that cannot be undone: asked in a sheet first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForgetSheet(onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val state = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = cs.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ShapeBox(MaterialShapes.Cookie7Sided, 64.dp, cs.errorContainer) {
                Icon(painterResource(R.drawable.rounded_link_off), null, tint = cs.onErrorContainer, modifier = Modifier.size(30.dp))
            }
            Text(stringResource(R.string.forget_title), style = Expressive.wide(26.sp))
            Text(stringResource(R.string.forget_confirm), style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
            FilledPill(stringResource(R.string.forget_action), onClick = {
                onDismiss()
                LinkHub.forget()
            }, container = cs.errorContainer, content = cs.onErrorContainer, modifier = Modifier.fillMaxWidth())
            DashedPill(stringResource(R.string.cancel), onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** The link in one row: its state, where it goes, and a retry when it is down. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun StatusRow(pc: PairedPc, status: LinkStatus) {
    ToolRow(
        title = statusLabel(status),
        summary = when (status) {
            is LinkStatus.Online -> status.host
            is LinkStatus.Offline -> problemText(status.problem)
            LinkStatus.Connecting -> pc.hosts.firstOrNull().orEmpty()
            else -> stringResource(R.string.link_hero_paired)
        },
        icon = R.drawable.rounded_desktop_windows,
        tint = when (status) {
            is LinkStatus.Online -> Tint.PRIMARY
            is LinkStatus.Offline -> Tint.ERROR
            else -> Tint.SECONDARY
        },
        trailing = {
            when (status) {
                LinkStatus.Connecting -> LoadingIndicator(Modifier.size(36.dp))
                is LinkStatus.Offline -> FilledTonalButton(onClick = { LinkHub.retry() }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.retry))
                }
                else -> {}
            }
        },
    )
}

@Composable
fun statusLabel(status: LinkStatus): String = stringResource(
    when (status) {
        LinkStatus.Unpaired -> R.string.status_unpaired
        LinkStatus.Idle -> R.string.status_idle
        LinkStatus.Pairing -> R.string.pair_working
        LinkStatus.Connecting -> R.string.status_connecting
        is LinkStatus.Online -> R.string.status_online
        is LinkStatus.Offline -> R.string.status_offline
    },
)

@Composable
internal fun problemText(problem: LinkProblem): String = stringResource(
    when (problem) {
        LinkProblem.UNREACHABLE -> R.string.problem_unreachable
        LinkProblem.REJECTED -> R.string.problem_rejected
        LinkProblem.PAIRING_FAILED -> R.string.problem_pairing
        LinkProblem.NO_PERMISSION -> R.string.problem_permission
    },
)

/**
 * The top of every screen that needs the PC: nothing while connected, else the link's
 * state with its retry. Returns whether the shell is there to talk to.
 */
internal fun LazyListScope.linkGate(pc: PairedPc?, status: LinkStatus, shellReady: Boolean): Boolean {
    if (pc == null) {
        item { Hint(stringResource(R.string.link_needed)) }
        return false
    }
    if (status is LinkStatus.Online && shellReady) return true
    item { CardGroup { StatusRow(pc, status) } }
    return false
}
