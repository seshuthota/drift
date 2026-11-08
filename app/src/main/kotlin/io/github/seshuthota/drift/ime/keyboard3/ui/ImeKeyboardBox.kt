/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.seshuthota.drift.ime.keyboard3.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastRoundToInt
import io.github.seshuthota.drift.app.DriftPreferenceStore
import io.github.seshuthota.drift.ime.core.DisplayLanguageNamesIn
import io.github.seshuthota.drift.ime.keyboard.DriftImeSizing
import io.github.seshuthota.drift.ime.keyboard.SpaceBarMode
import io.github.seshuthota.drift.glideTypingManager
import io.github.seshuthota.drift.ime.keyboard3.ImeIcons
import io.github.seshuthota.drift.ime.keyboard3.ImeLayerIds
import io.github.seshuthota.drift.ime.keyboard3.LocalImeController
import io.github.seshuthota.drift.ime.keyboard3.gesture.GestureListener
import io.github.seshuthota.drift.ime.keyboard3.gesture.GesturePoint
import io.github.seshuthota.drift.ime.keyboard3.gesture.GlideKey
import io.github.seshuthota.drift.ime.keyboard3.gesture.SwipeAction
import io.github.seshuthota.drift.ime.keyboard3.gesture.SwipeActionDispatcher
import io.github.seshuthota.drift.ime.keyboard3.gesture.SwipeDirection
import io.github.seshuthota.drift.ime.keyboard3.interaction.LongPress
import io.github.seshuthota.drift.ime.keyboard3.interaction.rememberPointerTracker
import io.github.seshuthota.drift.ime.keyboard3.interaction.trackPointerInput
import io.github.seshuthota.drift.ime.keyboard3.touch.InputShiftState
import io.github.seshuthota.drift.ime.keyboard3.touch.TouchKey
import io.github.seshuthota.drift.ime.keyboard3.touch.TouchKeyboard
import io.github.seshuthota.drift.ime.keyboard3.touch.isDeleteKey
import io.github.seshuthota.drift.ime.keyboard3.touch.isSpaceBarKey
import io.github.seshuthota.drift.ime.keyboard3.touch.TouchLayer
import io.github.seshuthota.drift.ime.keyboard3.touch.TouchModel
import io.github.seshuthota.drift.ime.theme.DriftImeUi
import io.github.seshuthota.drift.ime.theme.ImeUiAttrs
import io.github.seshuthota.drift.ime.theme.putAttr
import io.github.seshuthota.drift.ime.window.LocalWindowController
import io.github.seshuthota.drift.lib.DriftLocale
import io.github.seshuthota.drift.subtypeManager
import dev.patrickgold.jetpref.datastore.model.collectAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.seshuthota.drift.lib.compose.toMm
import io.github.seshuthota.drift.lib.snygg.SnyggQueryAttributes
import io.github.seshuthota.drift.lib.snygg.SnyggSelector
import io.github.seshuthota.drift.lib.snygg.ui.SnyggBox
import org.k3lp.lib.text.K3String
import org.k3lp.lib.text.K3StringOrDescriptor
import org.k3lp.lib.text.asK3String

@Composable
fun ImeKeyboardBox(
    modifier: Modifier = Modifier,
) {
    val prefs by DriftPreferenceStore
    val density = LocalDensity.current
    val context = LocalContext.current
    val glideTypingManager by context.glideTypingManager()
    val subtypeManager by context.subtypeManager()
    val imeController = LocalImeController.current
    val scope = rememberCoroutineScope()
    val windowController = LocalWindowController.current

    val imeState by imeController.activeState.collectAsState()
    val model by remember { derivedStateOf { imeState.model } }
    val touchLayerId by remember { derivedStateOf { imeState.touchLayerId } }
    val inputShiftState by remember { derivedStateOf { imeState.flags.inputShiftState } }

    val windowSpec by windowController.activeWindowSpec.collectAsState()
    val keyMarginHPx by remember {
        derivedStateOf { with(density) { windowSpec.keyMarginH.toPx() } }
    }
    val keyMarginVPx by remember {
        derivedStateOf { with(density) { windowSpec.keyMarginV.toPx() } }
    }

    val touchModelOptions by imeController.activeTouchModelOptions.collectAsState()
    var activeTouchModel by remember {
        mutableStateOf(imeController.touchModelCache.getFor(model, touchModelOptions) ?: TouchModel.Empty)
    }
    LaunchedEffect(model, touchModelOptions) {
        activeTouchModel = withContext(Dispatchers.Default) {
            imeController.touchModelCache.getOrComputeFor(model, touchModelOptions)
        }
    }

    val spaceBarMode by prefs.keyboard.spaceBarMode.collectAsState()
    val displayLanguageNamesIn by prefs.localization.displayLanguageNamesIn.collectAsState()
    val spaceBarDisplayOverride = remember(model, spaceBarMode, displayLanguageNamesIn) {
        when (spaceBarMode) {
            SpaceBarMode.NOTHING -> K3String.Empty
            SpaceBarMode.CURRENT_LANGUAGE -> {
                // TODO this might not be the right locale, get from new suntypes once impl
                val locale = DriftLocale.from(model.locales.getOrElse(0) { "en" })
                when (displayLanguageNamesIn) {
                    DisplayLanguageNamesIn.SYSTEM_LOCALE -> locale.displayName()
                    DisplayLanguageNamesIn.NATIVE_LOCALE -> locale.displayName(locale)
                }.asK3String()
            }
            SpaceBarMode.SPACE_BAR_KEY -> ImeIcons.SpaceBar
        }
    }

    val devtoolsEnabled by prefs.devtools.enabled.collectAsState()
    val debugShowTouchBoundaries by prefs.devtools.showKeyTouchBoundaries.collectAsState()

    BoxWithConstraints {
        val deviceWidthMm = remember(constraints.maxWidth) {
            with(density) { constraints.maxWidth.toDp().toMm().toInt() }
        }
        val activeTouchKeyboard = remember(activeTouchModel, deviceWidthMm) {
            activeTouchModel.selectKeyboard(deviceWidthMm)
        }
        // TODO this launched effect is a hack, there must be a better way
        LaunchedEffect(activeTouchKeyboard) {
            imeController.updateState {
                state = state.copy(effRowCount = activeTouchKeyboard.rowCount)
            }
        }
        val activeTouchLayer = remember(activeTouchKeyboard, touchLayerId) {
            if (touchLayerId == ImeLayerIds.Caps) {
                activeTouchKeyboard.layers[touchLayerId]
                    ?: activeTouchKeyboard.layers[ImeLayerIds.Shift]
                    ?: activeTouchKeyboard.layers[ImeLayerIds.Base]
            } else {
                activeTouchKeyboard.layers[touchLayerId]
                    ?: activeTouchKeyboard.layers[ImeLayerIds.Base]

            } ?: TouchLayer.Empty
        }

        val activeSubtype by subtypeManager.activeSubtypeFlow.collectAsState()
        val glideTrail by glideTypingManager.trail.collectAsState()
        val glideKeys = remember(activeTouchKeyboard) { buildGlideKeys(activeTouchKeyboard) }
        LaunchedEffect(glideKeys, activeSubtype) {
            if (glideKeys.isNotEmpty()) {
                glideTypingManager.setLayout(glideKeys, activeSubtype)
            }
        }
        val swipeDispatcher = remember(imeController) { SwipeActionDispatcher(imeController, scope) }
        val gestureListener = remember(swipeDispatcher, glideTypingManager) {
            object : GestureListener {
                override fun onSwipe(
                    direction: SwipeDirection,
                    unitCountX: Int,
                    unitCountY: Int,
                    origin: TouchKey?,
                ) {
                    val action = when {
                        origin?.isSpaceBarKey() == true -> when (direction) {
                            SwipeDirection.LEFT -> prefs.gestures.spaceBarSwipeLeft.get()
                            SwipeDirection.RIGHT -> prefs.gestures.spaceBarSwipeRight.get()
                            SwipeDirection.UP -> prefs.gestures.spaceBarSwipeUp.get()
                            else -> SwipeAction.NO_ACTION
                        }
                        origin?.isDeleteKey() == true -> if (direction == SwipeDirection.LEFT) {
                            prefs.gestures.deleteKeySwipeLeft.get()
                        } else {
                            SwipeAction.NO_ACTION
                        }
                        else -> when (direction) {
                            SwipeDirection.UP -> prefs.gestures.swipeUp.get()
                            SwipeDirection.DOWN -> prefs.gestures.swipeDown.get()
                            SwipeDirection.LEFT -> prefs.gestures.swipeLeft.get()
                            SwipeDirection.RIGHT -> prefs.gestures.swipeRight.get()
                            else -> SwipeAction.NO_ACTION
                        }
                    }
                    swipeDispatcher.dispatch(action, unitCountX, unitCountY)
                }

                override fun onKeyLongPress(key: TouchKey): Boolean {
                    val action = when {
                        key.isSpaceBarKey() -> prefs.gestures.spaceBarLongPress.get()
                            .takeIf { it != SwipeAction.NO_ACTION && it != SwipeAction.INSERT_SPACE }
                        key.isDeleteKey() -> prefs.gestures.deleteKeyLongPress.get()
                            .takeIf { it != SwipeAction.NO_ACTION }
                        else -> null
                    } ?: return false
                    swipeDispatcher.dispatch(action, 0, 0)
                    return true
                }

                override fun onGlideStart() {
                    glideTypingManager.onGlideStart()
                }

                override fun onGlidePoint(x: Float, y: Float) {
                    glideTypingManager.onGlidePoint(GesturePoint(x, y, timeMillis = 0L))
                }

                override fun onGlideComplete(points: List<GesturePoint>) {
                    glideTypingManager.onGlideComplete(points)
                }

                override fun onGlideCancel() {
                    glideTypingManager.onGlideCancel()
                }
            }
        }

        val pointerTracker = rememberPointerTracker(activeTouchKeyboard, gestureListener)
        val trackedOutputPointer by pointerTracker.trackedOutputPointer.collectAsState()
        val trackedPeekPointer by pointerTracker.trackedPeekPointer.collectAsState()
        val trackedMultiTapSeq by pointerTracker.trackedMultiTapSeq.collectAsState()

        val keyboardRowHeightDp = DriftImeSizing.keyboardRowBaseHeight
        val peekLineWidthPx = with(density) { 8.dp.toPx() }

        Box(
            modifier = modifier
                .layout { measurable, constraints ->
                    val effConstraints = Constraints.fixed(
                        width = constraints.maxWidth,
                        height = with(density) {
                            (keyboardRowHeightDp * activeTouchKeyboard.rowCount).roundToPx()
                        },
                    )
                    val placeable = measurable.measure(effConstraints)
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                .trackPointerInput(pointerTracker, model)
                .drawWithContent {
                    drawContent()
                    trackedPeekPointer?.let { trackedPeekPointer ->
                        val peekLine = trackedPeekPointer.peekLine
                        if (peekLine != null) {
                            drawLine(
                                color = Color.Red, // TODO customizable
                                start = peekLine.start,
                                end = peekLine.end,
                                strokeWidth = peekLineWidthPx,
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                    if (glideTrail.size > 1) {
                        val path = Path()
                        path.moveTo(glideTrail[0].x * size.width, glideTrail[0].y * size.height)
                        for (i in 1 until glideTrail.size) {
                            path.lineTo(glideTrail[i].x * size.width, glideTrail[i].y * size.height)
                        }
                        drawPath(
                            path = path,
                            color = Color(0xFF2196F3).copy(alpha = 0.7f), // TODO customizable
                            style = Stroke(
                                width = peekLineWidthPx,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                            ),
                        )
                    }
                }
        ) {
            for (touchKey in activeTouchLayer.keys) {
                if (touchKey.attrs.gap) {
                    continue
                }
                val touchKey by rememberUpdatedState(touchKey)
                val isPressed by remember {
                    derivedStateOf {
                        trackedOutputPointer?.downKey == touchKey ||
                            trackedPeekPointer?.let {
                                it.peekKey == touchKey || it.downKey.isShiftKey && touchKey.isShiftKey
                            } == true
                    }
                }
                val longPress by remember {
                    derivedStateOf {
                        trackedOutputPointer?.takeIf { it.downKey == touchKey }?.longPress ?: LongPress.None
                    }
                }
                val multiTapIndex by remember {
                    derivedStateOf {
                        trackedMultiTapSeq?.takeIf { it.touchKey == touchKey }?.multiTapIndex ?: -1
                    }
                }
                ImeKeyboardKeyBox(
                    touchKey = touchKey,
                    displayOverride = when {
                        touchKey.isSuitableForSpaceBarDisplayOverride -> spaceBarDisplayOverride
                        else -> null
                    },
                    isPressed = isPressed,
                    longPress = longPress,
                    multiTapIndex = multiTapIndex,
                    modifier = Modifier
                        .layout { measurable, constraints ->
                            val effConstraints = Constraints.fixed(
                                width = (constraints.maxWidth * touchKey.bounds.width - 2 * keyMarginHPx).fastRoundToInt(),
                                height = (constraints.maxHeight * touchKey.bounds.height - 2 * keyMarginVPx).fastRoundToInt(),
                            )
                            val placeable = measurable.measure(effConstraints)
                            val offset = IntOffset(
                                x = (constraints.maxWidth * touchKey.bounds.topLeft.x + keyMarginHPx).fastRoundToInt(),
                                y = (constraints.maxHeight * touchKey.bounds.topLeft.y + keyMarginVPx).fastRoundToInt(),
                            )
                            layout(placeable.width, placeable.height) { placeable.place(offset) }
                        },
                    inputShiftState = inputShiftState,
                )
                if (devtoolsEnabled && debugShowTouchBoundaries) {
                    Box(
                        modifier = Modifier
                            .layoutNormalized(touchKey.hitbox)
                            .drawWithContent {
                                drawContent()
                                drawRect(
                                    color = Color.Red,
                                    style = Stroke(width = 1.dp.toPx()),
                                )
                            }
                    )
                }
            }
        }
    }
}

private fun buildGlideKeys(touchKeyboard: TouchKeyboard): List<GlideKey> {
    val layer = touchKeyboard.layers[ImeLayerIds.Base] ?: return emptyList()
    return layer.keys.mapNotNull { key ->
        val text = (key.attrs.output as? K3String)?.toText() ?: return@mapNotNull null
        if (text.length != 1) return@mapNotNull null
        val bounds = key.bounds
        if (bounds.width <= 0f || bounds.height <= 0f) return@mapNotNull null
        GlideKey(
            code = text[0].code,
            centerX = (bounds.left + bounds.right) / 2f,
            centerY = (bounds.top + bounds.bottom) / 2f,
            width = bounds.width,
            height = bounds.height,
        )
    }
}

@Composable
private fun ImeKeyboardKeyBox(
    touchKey: TouchKey,
    displayOverride: K3StringOrDescriptor?,
    isPressed: Boolean,
    longPress: LongPress,
    multiTapIndex: Int,
    inputShiftState: InputShiftState,
    modifier: Modifier = Modifier,
) {
    val display = displayOverride ?: touchKey.display
    val output = touchKey.attrs.output
    val layerId = touchKey.attrs.layerId
    val attributes: SnyggQueryAttributes = remember(output, layerId, inputShiftState) {
        buildMap {
            if (output != null) {
                putAttr(ImeUiAttrs.Output, output)
            }
            if (layerId != null) {
                putAttr(ImeUiAttrs.TargetLayer, layerId)
            }
            putAttr(ImeUiAttrs.ShiftState, inputShiftState)
        }
    }
    // TODO some keys an be disabled (copy cut etc)
    val selector = if (isPressed) SnyggSelector.PRESSED else SnyggSelector.NONE
    val multiTapKeys = touchKey.multiTapKeys

    SnyggBox(
        DriftImeUi.Key.elementName,
        attributes = attributes,
        selector = selector,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .scaleToFitHorizontally(),
        ) {
            if (touchKey.shouldOverrideDisplayWithMultiTapKeys) {
                for ((index, multiTapKey) in multiTapKeys.withIndex()) {
                    Display3(
                        modifier = if (touchKey.shouldHighlightPendingMultiTapKey && index == multiTapIndex) {
                            Modifier.background(Color.Red) // TODO customizable
                        } else Modifier,
                        display = multiTapKey.display,
                    )
                }
            } else {
                Display3(display)
            }
        }
        if (touchKey.longPressKeyHint != null) {
            Display3(
                elementName = DriftImeUi.KeyHint.elementName,
                attributes = attributes,
                modifier = Modifier
                    .align(touchKey.longPressKeyHintPlacement.alignment)
                    .scaleToFitHorizontally(),
                display = touchKey.longPressKeyHint,
            )
        }
        // TODO flick hints
    }
    LongPressBox(
        longPress = longPress,
        longPressSimpleDisplayOverride = multiTapKeys.getOrNull(multiTapIndex)?.display,
        attributes = attributes,
    )
}
