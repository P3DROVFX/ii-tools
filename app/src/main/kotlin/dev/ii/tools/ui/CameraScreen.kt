@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.ii.tools.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ii.tools.R
import dev.ii.tools.ui.theme.Expressive
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/*
 * The phone as the PC's webcam (ROADMAP 4.6, the DroidCam replacement). Not built yet:
 * this is the screen it will have, held sideways, with only what matters (lens, zoom,
 * exposure, focus). The controls move but drive nothing until the camera stream exists.
 */

private val lensLabels = listOf("0.6", "1×", "3×")

/** The lens chips: the chosen one a rounded square, the others circles. [withFront] adds the selfie camera at 0. */
@Composable
internal fun LensPicker(
    lens: Int,
    onPick: (Int) -> Unit,
    idle: Color,
    chosen: Color,
    onChosen: Color,
    modifier: Modifier = Modifier,
    withFront: Boolean = false,
    size: Dp = 48.dp,
) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val count = if (withFront) 4 else 3
        for (i in 0 until count) {
            val index = if (withFront) i else i + 1
            val on = index == lens
            Box(
                Modifier
                    .size(size)
                    .clip(if (on) RoundedCornerShape(16.dp) else CircleShape)
                    .background(if (on) chosen else idle)
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onPick(index)
                    }
                    .semantics { role = Role.RadioButton },
                contentAlignment = Alignment.Center,
            ) {
                if (index == 0) {
                    Icon(painterResource(R.drawable.rounded_camera_front), stringResource(R.string.webcam_front), tint = if (on) onChosen else cs.onSurface, modifier = Modifier.size(22.dp))
                } else {
                    Text(lensLabels[index - 1], style = Expressive.digits(20.sp), color = if (on) onChosen else cs.onSurface)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CameraScreen(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var lens by remember { mutableIntStateOf(2) }
    var zoom by remember { mutableFloatStateOf(0.4f) }
    var exposure by remember { mutableFloatStateOf(0.5f) }
    var manualFocus by remember { mutableStateOf(false) }
    var focus by remember { mutableFloatStateOf(0.5f) }

    Row(
        Modifier.fillMaxSize().background(Layer.page).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(28.dp)).background(Layer.field)) {
            Ornament(MaterialShapes.Cookie12Sided, 420.dp, cs.onSurfaceVariant, Alignment.BottomStart, x = (-120).dp, y = 220.dp, alpha = 0.14f)
            Ornament(MaterialShapes.Flower, 260.dp, cs.onSurfaceVariant, Alignment.TopEnd, x = 60.dp, y = (-90).dp, alpha = 0.1f)
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.rounded_videocam), null, tint = cs.onSurfaceVariant, modifier = Modifier.size(40.dp))
                Text(stringResource(R.string.coming_soon), style = Expressive.wide(20.sp), color = cs.onSurface)
                Text(stringResource(R.string.webcam_soon_body), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            Row(
                Modifier.align(Alignment.TopStart).padding(14.dp).clip(CircleShape).background(cs.surfaceContainerHigh).padding(start = 12.dp, end = 14.dp).height(36.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(cs.outline))
                Text(stringResource(R.string.webcam_not_live), style = Expressive.title(14.sp))
            }
            FilledPill(
                stringResource(R.string.webcam_start),
                onClick = {},
                enabled = false,
                icon = R.drawable.rounded_play_arrow_fill1,
                height = 44.dp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp),
            )
        }

        Column(
            Modifier.width(268.dp).fillMaxHeight().clip(RoundedCornerShape(28.dp)).background(Layer.pane).verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledIconButton(
                    onClick = onBack,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Layer.onPane, contentColor = cs.onSurface),
                    modifier = Modifier.size(40.dp),
                ) { Icon(painterResource(R.drawable.rounded_arrow_back), stringResource(R.string.back), Modifier.size(22.dp)) }
                Text(stringResource(R.string.webcam_title), style = Expressive.wide(22.sp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Caption(stringResource(R.string.webcam_lens), color = cs.onSurfaceVariant)
                LensPicker(lens, onPick = { lens = it }, idle = Layer.onPane, chosen = cs.primary, onChosen = cs.onPrimary, withFront = true)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Caption(stringResource(R.string.webcam_zoom), color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text("%.1f×".format(1f + zoom * 4f), style = Expressive.digits(26.sp))
                }
                BigSlider(zoom, { zoom = it }, {}, stringResource(R.string.webcam_zoom), height = 28.dp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(painterResource(R.drawable.rounded_exposure), null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Caption(stringResource(R.string.webcam_exposure), color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text("%+.1f".format((exposure - 0.5f) * 4f), style = Expressive.digitsSoft(18.sp))
                }
                BigSlider(exposure, { exposure = it }, {}, stringResource(R.string.webcam_exposure), height = 24.dp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Caption(stringResource(R.string.webcam_focus), color = cs.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                    listOf(
                        Triple(false, R.string.webcam_focus_auto, R.drawable.rounded_center_focus_strong),
                        Triple(true, R.string.webcam_focus_manual, R.drawable.rounded_filter_center_focus),
                    ).forEachIndexed { i, (manual, label, icon) ->
                        ToggleButton(
                            checked = manualFocus == manual,
                            onCheckedChange = { manualFocus = manual },
                            shapes = if (i == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes(),
                            modifier = Modifier.weight(1f).height(44.dp).semantics { role = Role.RadioButton },
                        ) {
                            Icon(painterResource(icon), null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(label), style = Expressive.title(14.sp))
                        }
                    }
                }
                if (manualFocus) BigSlider(focus, { focus = it }, {}, stringResource(R.string.webcam_focus), height = 24.dp)
            }
        }
    }
}
