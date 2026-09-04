package com.android.systemui.axdynamicbar.ui

import android.content.Context
import android.graphics.Color.TRANSPARENT
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.AxDynamicBarTheme
import com.android.systemui.axdynamicbar.shared.ExpandedMaxWidth
import com.android.systemui.axdynamicbar.ui.compose.ExpandedContentBottomScrollPadding
import com.android.systemui.axdynamicbar.ui.compose.ExpandedIslandContent
import com.android.systemui.axdynamicbar.ui.compose.screenBounds
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.shared.recents.utilities.Utilities
import com.android.systemui.statusbar.phone.ComponentSystemUIDialog
import com.android.systemui.statusbar.phone.DialogDelegate
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.phone.SystemUIDialogFactory
import com.android.systemui.statusbar.phone.create
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

private val PanelCollapsedRadius = 12.dp
private val PanelExpandedRadius = 28.dp
private const val PanelCollapsedScaleX = 0.24f
private const val PanelCollapsedScaleY = 0.16f

private val BouncyOpenSpring =
    spring<Float>(
        dampingRatio = 0.58f,
        stiffness = 380f,
    )

private val BouncyCloseSpring =
    spring<Float>(
        dampingRatio = 0.70f,
        stiffness = 500f,
    )

@SysUISingleton
class AxDynamicBarExpandedPanel
@Inject
constructor(
    @Application private val context: Context,
    @Application private val applicationScope: CoroutineScope,
    @Main private val mainExecutor: Executor,
    private val dialogFactory: SystemUIDialogFactory,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    private val viewModel: AxDynamicBarChipViewModel,
) {
    private var currentDialog: ComponentSystemUIDialog? = null

    fun init() {
        viewModel.interactor.onCollapseRequested = { viewModel.statusBarExpansion.collapse() }
        viewModel.interactor.onFocusableRequested = { focusable -> setDialogFocusable(focusable) }

        combine(viewModel.isExpanded, viewModel.isOnKeyguard) { expanded, onKeyguard ->
                expanded && !onKeyguard
            }
            .onEach { shouldShow ->
                if (shouldShow) {
                    showDialog()
                } else if (viewModel.isOnKeyguard.value) {
                    dismissImmediately()
                }
            }
            .launchIn(applicationScope)
    }

    private fun ensureMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainExecutor.execute(action)
    }

    private fun showDialog() = ensureMainThread {
        if (currentDialog != null) return@ensureMainThread

        val dialog =
            dialogFactory.create(
                context = context,
                dismissOnDeviceLock = true,
                dialogDelegate = dialogDelegate(),
            ) {
                ExpandedPanelDialogContent(
                    viewModel = viewModel,
                    onDismissComplete = { dismissImmediately() },
                )
            }

        configureDialogWindow(dialog)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener {
            currentDialog = null
            if (viewModel.isExpanded.value) {
                viewModel.statusBarExpansion.collapse()
            }
        }
        currentDialog = dialog
        dialog.show()
    }

    private fun dismissImmediately() = ensureMainThread {
        currentDialog?.dismiss()
        currentDialog = null
    }

    private fun setDialogFocusable(focusable: Boolean) = ensureMainThread {
        currentDialog?.window?.let { window ->
            if (focusable) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            } else {
                window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            }
        }
    }

    private fun dialogDelegate(): DialogDelegate<SystemUIDialog> =
        object : DialogDelegate<SystemUIDialog> {
            override fun onCreate(dialog: SystemUIDialog, savedInstanceState: Bundle?) {
                configureDialogWindow(dialog)
            }

            override fun onStart(dialog: SystemUIDialog) {
                configureDialogWindow(dialog)
            }

            override fun getWidth(dialog: SystemUIDialog): Int =
                WindowManager.LayoutParams.MATCH_PARENT

            override fun getHeight(dialog: SystemUIDialog): Int =
                WindowManager.LayoutParams.MATCH_PARENT
        }

    private fun configureDialogWindow(dialog: SystemUIDialog) {
        val window = dialog.window ?: return
        window.setGravity(Gravity.TOP or Gravity.FILL_HORIZONTAL)
        window.setBackgroundDrawable(GradientDrawable().apply { setColor(TRANSPARENT) })
        window.decorView.background = GradientDrawable().apply { setColor(TRANSPARENT) }
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        val attributes = window.attributes
        attributes.dimAmount = 0f
        attributes.gravity = Gravity.TOP or Gravity.FILL_HORIZONTAL
        attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        attributes.windowAnimations = 0
        window.attributes = attributes
    }
}

@Composable
private fun ExpandedPanelDialogContent(
    viewModel: AxDynamicBarChipViewModel,
    onDismissComplete: () -> Unit,
) {
    AxDynamicBarTheme {
        ExpandedPanelDialogContentBody(viewModel, onDismissComplete)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ExpandedPanelDialogContentBody(
    viewModel: AxDynamicBarChipViewModel,
    onDismissComplete: () -> Unit,
) {
    val density = LocalDensity.current
    val rootView = LocalView.current
    val isLargeScreen = Utilities.isLargeScreen(LocalContext.current)
    val topPad =
        with(density) { WindowInsets.statusBars.getTop(this).toDp() } +
            4.dp +
            if (isLargeScreen) 4.dp else 0.dp
    val chipState by viewModel.chipState.collectAsStateWithLifecycle()
    val isExpanded by viewModel.isExpanded.collectAsStateWithLifecycle()
    val chipX by viewModel.chipCenterXFraction.collectAsStateWithLifecycle()
    val chipBounds by viewModel.chipBounds.collectAsStateWithLifecycle()
    var rootBounds by remember { mutableStateOf<Rect?>(null) }
    var panelBounds by remember { mutableStateOf<Rect?>(null) }
    var panelHasScrollableOverflow by remember { mutableStateOf(false) }
    val panelProgress = remember { Animatable(0f) }
    val bottomScrollPaddingPx = with(density) { ExpandedContentBottomScrollPadding.toPx() }

    BackHandler(enabled = isExpanded) {
        viewModel.statusBarExpansion.collapse()
    }

    LaunchedEffect(chipState) {
        val filtered = chipState?.allEvents?.filter { it !is IslandEvent.AospChip }
        if (filtered.isNullOrEmpty()) {
            viewModel.statusBarExpansion.collapse()
        }
    }

    LaunchedEffect(isExpanded) {
        if (isExpanded) {
            panelProgress.animateTo(
                targetValue = 1f,
                animationSpec = BouncyOpenSpring,
            )
        } else {
            panelProgress.animateTo(
                targetValue = 0f,
                animationSpec = BouncyCloseSpring,
            )
            onDismissComplete()
        }
    }

    val originX = chipBounds?.centerXFraction ?: chipX
    val chipAlignment = BiasAlignment(horizontalBias = originX * 2f - 1f, verticalBias = -1f)
    val panelOriginX = panelTransformOriginX(chipBounds, panelBounds)
    val progress = panelProgress.value
    val clampedProgress = progress.coerceIn(0f, 1f)
    val panelRadius =
        PanelCollapsedRadius + (PanelExpandedRadius - PanelCollapsedRadius) * clampedProgress
    val panelTapBounds =
        remember(panelBounds, panelHasScrollableOverflow, bottomScrollPaddingPx) {
            if (panelHasScrollableOverflow) {
                panelBounds
            } else {
                panelBounds.withoutBottomPadding(bottomScrollPaddingPx)
            }
        }

    var lastValidEvents by remember { mutableStateOf<List<IslandEvent>>(emptyList()) }
    val currentFiltered =
        chipState?.allEvents?.filter { it !is IslandEvent.AospChip } ?: emptyList()
    if (currentFiltered.isNotEmpty()) {
        lastValidEvents = currentFiltered
    }
    val eventsToDisplay = if (currentFiltered.isNotEmpty()) currentFiltered else lastValidEvents

    Box(
        modifier =
            Modifier.fillMaxSize()
                .onGloballyPositioned { rootBounds = it.screenBounds(rootView) }
                .pointerInput(chipBounds, panelTapBounds, rootBounds, viewModel) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        var ev: PointerEvent
                        do {
                            ev = awaitPointerEvent(PointerEventPass.Final)
                        } while (!ev.changes.any { it.changedToDownIgnoreConsumed() })
                        val down = ev.changes.first { it.changedToDownIgnoreConsumed() }
                        val downPos = down.position
                        val root = rootBounds
                        val downScreenX = (root?.left ?: 0) + downPos.x
                        val downScreenY = (root?.top ?: 0) + downPos.y
                        val downConsumed = down.isConsumed
                        val downInChip =
                            chipBounds.containsWithPadding(downScreenX, downScreenY, slop * 2f)
                        val downInPanel =
                            panelTapBounds == null ||
                                panelTapBounds.containsWithPadding(downScreenX, downScreenY, slop)
                        var horizontalChipDrag = false
                        var totalDx = 0f
                        var totalDy = 0f

                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                            val change = event.changes.firstOrNull() ?: break
                            totalDx = change.position.x - downPos.x
                            totalDy = change.position.y - downPos.y
                            if (!change.pressed) {
                                if (downInChip) {
                                    if (horizontalChipDrag) {
                                        change.consume()
                                        viewModel.handleChipRelease(
                                            totalDx,
                                            totalDy,
                                            slop,
                                            horizontalChipDrag,
                                        )
                                    } else {
                                        viewModel.collapseIfTap(totalDx, totalDy, slop)
                                    }
                                } else if (!downInPanel && !downConsumed && !change.isConsumed) {
                                    viewModel.collapseIfTap(totalDx, totalDy, slop)
                                }
                                break
                            }
                            if (downInChip && !horizontalChipDrag) {
                                if (abs(totalDx) > slop || abs(totalDy) > slop) {
                                    horizontalChipDrag = abs(totalDx) >= abs(totalDy)
                                    if (horizontalChipDrag) {
                                        change.consume()
                                    }
                                }
                            } else if (horizontalChipDrag) {
                                change.consume()
                            }
                        }
                    }
                }
                .padding(top = topPad, bottom = 8.dp),
        contentAlignment = chipAlignment,
    ) {
        if (eventsToDisplay.isNotEmpty()) {
            val state = chipState
            val pinnedEventId =
                state?.event?.id?.takeIf { id -> eventsToDisplay.any { it.id == id } }
                    ?: eventsToDisplay.first().id
            Box(
                modifier =
                    Modifier.widthIn(max = ExpandedMaxWidth)
                        .onGloballyPositioned { panelBounds = it.screenBounds(rootView) }
                        .graphicsLayer {
                            val scale = progress.coerceAtLeast(0f)
                            scaleX = PanelCollapsedScaleX + (1f - PanelCollapsedScaleX) * scale
                            scaleY = PanelCollapsedScaleY + (1f - PanelCollapsedScaleY) * scale
                            translationY = (1f - scale) * -16.dp.toPx()
                            alpha =
                                if (isExpanded) (progress * 2.5f).coerceIn(0f, 1f)
                                else (progress * 1.6f).coerceIn(0f, 1f)
                            transformOrigin = TransformOrigin(panelOriginX, 0f)
                        }
                        .clip(RoundedCornerShape(panelRadius))
            ) {
                Box(
                    modifier =
                        Modifier.graphicsLayer {
                            alpha = ((progress - 0.2f) / 0.8f).coerceIn(0f, 1f)
                        }
                ) {
                    ExpandedIslandContent(
                        events = eventsToDisplay,
                        interactor = viewModel.interactor,
                        onCollapse = { viewModel.statusBarExpansion.collapse() },
                        onScrollableOverflowChanged = { panelHasScrollableOverflow = it },
                        pinnedEventId = pinnedEventId,
                        hapticsViewModelFactory =
                            viewModel.interactor.sliderHapticsViewModelFactory,
                    )
                }
            }
        }
    }
}

private fun AxDynamicBarChipBounds?.containsWithPadding(
    x: Float,
    y: Float,
    padding: Float,
): Boolean =
    this != null &&
        x >= left - padding &&
        x <= right + padding &&
        y >= top - padding &&
        y <= bottom + padding

private fun Rect?.containsWithPadding(x: Float, y: Float, padding: Float): Boolean =
    this != null &&
        x >= left - padding &&
        x <= right + padding &&
        y >= top - padding &&
        y <= bottom + padding

private fun Rect?.withoutBottomPadding(padding: Float): Rect? =
    this?.let {
        Rect(it.left, it.top, it.right, (it.bottom - padding.toInt()).coerceAtLeast(it.top))
    }

private fun panelTransformOriginX(chipBounds: AxDynamicBarChipBounds?, panelBounds: Rect?): Float {
    if (chipBounds == null || panelBounds == null || panelBounds.width() <= 0) return 0.5f
    val chipCenter = (chipBounds.left + chipBounds.right) / 2f
    return ((chipCenter - panelBounds.left) / panelBounds.width()).coerceIn(0f, 1f)
}

private fun AxDynamicBarChipViewModel.handleChipRelease(
    dx: Float,
    dy: Float,
    slop: Float,
    wasHorizontalDrag: Boolean,
) {
    if (wasHorizontalDrag) {
        if (dx > 0f) cyclePrev() else cycleNext()
    } else {
        collapseIfTap(dx, dy, slop)
    }
}

private fun AxDynamicBarChipViewModel.collapseIfTap(dx: Float, dy: Float, slop: Float) {
    if (dx * dx + dy * dy <= slop * slop) {
        statusBarExpansion.collapse()
    }
}
