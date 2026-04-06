package com.android.systemui.axdynamicbar.ui.compose

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.graphics.drawable.Drawable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.res.stringResource
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.AlphaIconBg
import com.android.systemui.axdynamicbar.shared.AlphaSecondary
import com.android.systemui.axdynamicbar.shared.AlphaTertiary
import com.android.systemui.axdynamicbar.shared.PillPrimary
import com.android.systemui.axdynamicbar.shared.ShapeXl
import com.android.systemui.axdynamicbar.shared.ShapeXs
import com.android.systemui.axdynamicbar.shared.SizeBadge
import com.android.systemui.axdynamicbar.shared.SpaceMd
import com.android.systemui.axdynamicbar.shared.SpaceSm
import com.android.systemui.axdynamicbar.shared.SpaceXs
import com.android.systemui.axdynamicbar.shared.TsBadge
import com.android.systemui.axdynamicbar.shared.chipAccentColorFor
import com.android.systemui.axdynamicbar.shared.chipContentColorOn
import com.android.systemui.axdynamicbar.shared.chipProgressFor
import com.android.systemui.axdynamicbar.shared.displayKeyFor
import com.android.systemui.axdynamicbar.shared.iconKeyFor
import com.android.systemui.axdynamicbar.shared.textKeyFor
import com.android.systemui.axdynamicbar.shared.toScaledBitmap
import com.android.systemui.axdynamicbar.ui.AxDynamicBarChipViewModel
import com.android.systemui.res.R
import kotlin.math.abs

private val ChipShape = ShapeXl
private val ChipHeight = 24.dp
private const val ChipWidthFraction = 0.30f
private val ChipWidthCap = 180.dp
// SpaceSm(6) + icon(16) + SpaceXs(4) + SpaceMd(8)
private val ChipContentOverhead = 34.dp

@Composable
private fun chipMaxWidth(): Dp {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    return (screenWidth * ChipWidthFraction).coerceAtMost(ChipWidthCap)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AxDynamicBarChip(
    viewModel: AxDynamicBarChipViewModel,
    modifier: Modifier = Modifier,
    ignoreKeyguard: Boolean = false,
) {
    val state by viewModel.chipState.collectAsStateWithLifecycle()
    val isOnKeyguard by viewModel.isOnKeyguard.collectAsStateWithLifecycle()
    val isExpanded by viewModel.isExpanded.collectAsStateWithLifecycle()
    val keyguardCarrier by viewModel.keyguardCarrierText.collectAsStateWithLifecycle()

    val carrierName = if (isOnKeyguard && ignoreKeyguard) keyguardCarrier.takeIf { it.isNotBlank() } else null
    val chipMax = chipMaxWidth()
    val textMax = (chipMax - ChipContentOverhead).coerceAtLeast(40.dp)
    val screenWidthPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenWidthDp.dp.toPx()
    }

    val touchSlop = LocalViewConfiguration.current.touchSlop

    val motionScheme = MaterialTheme.motionScheme

    val chipVisible = state != null && !isExpanded && (ignoreKeyguard || !isOnKeyguard)
    val actualVisible = remember { androidx.compose.animation.core.MutableTransitionState(false) }
    androidx.compose.runtime.LaunchedEffect(chipVisible) {
        if (chipVisible) {
            // Delay reappearance so panel can start its exit animation first
            kotlinx.coroutines.delay(150)
        }
        actualVisible.targetState = chipVisible
    }

    AnimatedVisibility(
        visibleState = actualVisible,
        enter = fadeIn(motionScheme.defaultEffectsSpec()) + scaleIn(initialScale = 0.8f, animationSpec = motionScheme.defaultSpatialSpec()),
        exit = fadeOut(motionScheme.fastEffectsSpec()) + scaleOut(targetScale = 0.8f, animationSpec = motionScheme.fastSpatialSpec()),
        modifier = modifier
            .pointerInput(viewModel) {
                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Initial)
                    
                    val startX = down.position.x
                    val startY = down.position.y
                    var dragging = false
                    var totalDx = 0f
                    var decided = false 
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) {
                            
                            if (dragging) {
                                change.consume()
                                if (totalDx > 0) viewModel.cyclePrev()
                                else viewModel.cycleNext()
                            } else if (!decided) {
                                
                                change.consume()
                                viewModel.togglePanel()
                            }
                            
                            break
                        }
                        val dx = change.position.x - startX
                        val dy = change.position.y - startY
                        if (!decided && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                            if (abs(dx) >= abs(dy)) {
                                
                                decided = true
                                dragging = true
                                totalDx = dx
                                change.consume()
                            } else {
                                
                                decided = true
                                break
                            }
                        } else if (dragging) {
                            totalDx = dx
                            change.consume()
                        }
                    }
                }
            }
            .onGloballyPositioned { coords ->
                val bounds = coords.boundsInWindow()
                if (bounds.isEmpty) return@onGloballyPositioned
                val centerX = (bounds.left + bounds.right) / 2f
                if (screenWidthPx > 0f) {
                    viewModel.updateChipCenterX(centerX / screenWidthPx)
                    viewModel.updateChipWidthPx(bounds.width.toInt())
                }
            },
    ) {
        state?.let { chipState ->
            val displayEvent = chipState.notificationAlert ?: chipState.event
            val isAlert = chipState.notificationAlert != null

            AnimatedContent(
                targetState = ChipDisplay(displayEvent, isAlert),
                transitionSpec = {
                    fadeIn() togetherWith fadeOut() using SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> motionScheme.defaultSpatialSpec() })
                },
                contentKey = { chipDisplayKey(it) },
                label = "chip_event",
            ) { display ->
                val rawAccent = chipAccentColorFor(display.event)
                val accent by animateColorAsState(rawAccent, MaterialTheme.motionScheme.fastEffectsSpec(), label = "accent")
                val contentColor by animateColorAsState(
                    chipContentColorOn(rawAccent), MaterialTheme.motionScheme.fastEffectsSpec(), label = "content",
                )
                val rawProgress = chipProgressFor(display.event)
                val progressTarget = rawProgress ?: 0f
                val animatedProgress by animateFloatAsState(
                    progressTarget, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "progress",
                )
                val progress = if (rawProgress != null) animatedProgress else null

                Box(
                    modifier = Modifier.fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        modifier =
                            Modifier.height(ChipHeight)
                                .widthIn(max = chipMax)
                                .clip(ChipShape)
                                .background(accent)
                                .then(
                                    if (progress != null) {
                                        val trackColor = lerp(accent, contentColor, 0.2f)
                                        val fillColor = lerp(accent, contentColor, 0.6f)
                                        Modifier.drawWithContent {
                                            drawContent()
                                            val barH = 2.dp.toPx()
                                            val y = size.height - barH
                                            drawRect(
                                                trackColor,
                                                topLeft = Offset(0f, y),
                                                size = Size(size.width, barH),
                                            )
                                            drawRect(
                                                fillColor,
                                                topLeft = Offset(0f, y),
                                                size = Size(size.width * progress, barH),
                                            )
                                        }
                                    } else Modifier
                                )
                                .padding(start = SpaceSm, end = SpaceMd),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (carrierName != null) {
                            Text(
                                text = carrierName,
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor.copy(alpha = AlphaSecondary),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 56.dp),
                            )
                            Text(
                                text = " · ",
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor.copy(alpha = AlphaTertiary),
                            )
                        }
                        if (display.isAlert && display.event is IslandEvent.Notification) {
                            AnimatedContent(
                                targetState = display.event,
                                transitionSpec = {
                                    (fadeIn(motionScheme.defaultEffectsSpec()) togetherWith fadeOut(motionScheme.fastEffectsSpec()))
                                        .using(sizeTransform = null)
                                },
                                contentKey = {
                                    (it as? IslandEvent.Notification)?.sbn?.key
                                },
                                label = "alert_content",
                            ) { event ->
                                val notif = event as IslandEvent.Notification
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    notif.appIcon?.let { icon ->
                                        Image(
                                            bitmap = icon.toScaledBitmap(16.dp),
                                            contentDescription = null,
                                            modifier =
                                                Modifier.size(16.dp)
                                                    .clip(ShapeXs),
                                            contentScale = ContentScale.Crop,
                                        )
                                        Spacer(Modifier.width(SpaceXs))
                                    }
                                    Text(
                                        text = notif.appName ?: "",
                                        style = PillPrimary,
                                        color = contentColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = textMax).basicMarquee(),
                                    )
                                }
                            }
                        } else if (display.event is IslandEvent.Sports && (display.event as IslandEvent.Sports).team2Name.isNotEmpty()) {
                            val sport = display.event as IslandEvent.Sports
                            StatusBarSportsTeamBadge(sport.team1Name, sport.team1Icon, contentColor)
                            Spacer(Modifier.width(SpaceXs))
                            Text(
                                if (sport.score1.isNotEmpty()) "${sport.score1} - ${sport.score2}"
                                    else stringResource(R.string.ax_dynamic_bar_sports_vs),
                                style = PillPrimary,
                                color = contentColor,
                                maxLines = 1,
                                modifier = Modifier.widthIn(max = (chipMax - 74.dp).coerceAtLeast(20.dp)),
                            )
                            Spacer(Modifier.width(SpaceXs))
                            StatusBarSportsTeamBadge(sport.team2Name, sport.team2Icon, contentColor)
                        } else {
                            AnimatedContent(
                                targetState = display.event,
                                transitionSpec = {
                                    (fadeIn(motionScheme.defaultEffectsSpec()) togetherWith fadeOut(motionScheme.fastEffectsSpec()))
                                        .using(sizeTransform = null)
                                },
                                contentKey = { iconKeyFor(it) },
                                label = "chip_icon",
                            ) { event ->
                                PillEventIcon(event, tint = contentColor)
                            }
                            Spacer(Modifier.width(SpaceXs))
                            AnimatedContent(
                                targetState = display.event,
                                transitionSpec = {
                                    (fadeIn(motionScheme.defaultEffectsSpec()) togetherWith fadeOut(motionScheme.fastEffectsSpec()))
                                        .using(sizeTransform = null)
                                },
                                contentKey = { textKeyFor(it) },
                                label = "chip_text",
                                modifier = Modifier.weight(1f, fill = false),
                            ) { event ->
                                PillEventText(
                                    event,
                                    Modifier.widthIn(max = textMax),
                                    overrideColor = contentColor,
                                )
                            }
                            if (chipState.eventCount > 1) {
                                Spacer(Modifier.width(SpaceXs))
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .height(SizeBadge)
                                        .widthIn(min = SizeBadge)
                                        .background(
                                            lerp(accent, contentColor, 0.3f),
                                            RoundedCornerShape(SizeBadge / 2),
                                        )
                                        .padding(horizontal = 3.dp),
                                ) {
                                    Text(
                                        text = "${chipState.eventCount}",
                                        style = TsBadge,
                                        color = contentColor,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBarSportsTeamBadge(name: String, icon: Drawable?, contentColor: Color) {
    val badgeSize = 16.dp
    if (icon != null) {
        Image(
            bitmap = icon.toScaledBitmap(badgeSize),
            contentDescription = name,
            modifier = Modifier.size(badgeSize).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = Modifier.size(badgeSize).clip(CircleShape)
                .background(contentColor.copy(alpha = AlphaIconBg)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.take(2).uppercase(),
                style = TsBadge,
                color = contentColor,
            )
        }
    }
}

private data class ChipDisplay(val event: IslandEvent, val isAlert: Boolean)

private fun chipDisplayKey(display: ChipDisplay): Any =
    displayKeyFor(display.event, display.isAlert)

