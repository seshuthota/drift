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

package io.github.seshuthota.drift.ime.keyboard3.interaction

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastCoerceAtLeast
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastFold
import androidx.compose.ui.util.fastForEach
import io.github.seshuthota.drift.app.DriftPreferenceStore
import io.github.seshuthota.drift.ime.keyboard3.ImeActions
import io.github.seshuthota.drift.ime.keyboard3.ImeController
import io.github.seshuthota.drift.ime.keyboard3.ImeLayerIds
import io.github.seshuthota.drift.ime.keyboard3.LocalImeController
import io.github.seshuthota.drift.ime.keyboard3.gesture.GestureConfig
import io.github.seshuthota.drift.ime.keyboard3.gesture.GestureListener
import io.github.seshuthota.drift.ime.keyboard3.gesture.GestureOutcome
import io.github.seshuthota.drift.ime.keyboard3.gesture.GesturePoint
import io.github.seshuthota.drift.ime.keyboard3.gesture.GestureRecognizer
import io.github.seshuthota.drift.ime.keyboard3.gesture.GestureSignal
import io.github.seshuthota.drift.ime.keyboard3.touch.InputShiftState
import io.github.seshuthota.drift.ime.keyboard3.touch.TouchKey
import io.github.seshuthota.drift.ime.keyboard3.touch.TouchKeyboard
import io.github.seshuthota.drift.ime.keyboard3.touch.isDeleteKey
import io.github.seshuthota.drift.ime.keyboard3.touch.isSpaceBarKey
import dev.patrickgold.jetpref.datastore.model.collectAsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.k3lp.lib.text.K3Descriptor
import org.k3lp.lib.text.K3String
import org.k3lp.lib.text.asK3String
import org.k3lp.model.K3Model
import org.k3lp.model.layer.K3LayerId
import kotlin.math.pow
import kotlin.time.Duration

sealed interface TrackedPointer {
    val id: PointerId
    val down: PointerInputChange
    val downKey: TouchKey

    data class Peek(
        override val id: PointerId,
        override val down: PointerInputChange,
        override val downKey: TouchKey,
        val downLayerId: K3LayerId,
        val downInputShiftState: InputShiftState,
        val peekLayerId: K3LayerId,
        val peekKey: TouchKey?,
        val peekLine: PeekLine?,
        val peekMustSwitchBack: Boolean,
    ) : TrackedPointer

    data class Output(
        override val id: PointerId,
        override val down: PointerInputChange,
        override val downKey: TouchKey,
        val longPress: LongPress,
        val repeatJob: Job?,
        val keyActionJob: Job? = null,
        val keyActionConsumed: Boolean = false,
    ) : TrackedPointer
}

data class TrackedMultiTapSeq(
    val touchKey: TouchKey,
    val multiTapIndex: Int,
    val commitJob: Job?,
    val commit: suspend () -> Unit,
    val cycleNext: suspend () -> Unit,
)

data class PeekLine(
    val start: Offset,
    val end: Offset,
)

private data class LastUpKey(
    val touchKey: TouchKey,
    val uptimeMillis: Long,
)

class PointerTracker(
    val touchKeyboard: TouchKeyboard,
    val imeController: ImeController,
    val interactionController: InteractionController,
    val showSimplePopups: Boolean,
    val peekDistanceSqMin: Float,
    val gestureConfig: GestureConfig,
    val gestureListener: GestureListener?,
) {
    private val mutationGuard = Mutex()
    private val gestureRecognizer = GestureRecognizer()

    val trackedPeekPointer: StateFlow<TrackedPointer.Peek?>
        field = MutableStateFlow(null)

    val trackedOutputPointer: StateFlow<TrackedPointer.Output?>
        field = MutableStateFlow(null)

    val trackedMultiTapSeq: StateFlow<TrackedMultiTapSeq?>
        field = MutableStateFlow(null)

    private var lastUpKeyState: LastUpKey? = null

    suspend fun mutateLocked(action: suspend PointerTracker.() -> Unit) {
        mutationGuard.withLock { action() }
    }

    context(scope: CoroutineScope)
    suspend fun handleDown(down: PointerInputChange, size: IntSize) {
        val lastUpKey = lastUpKeyState

        trackedPeekPointer.value?.let { trackedPointer ->
            if (trackedPointer.peekKey != null) {
                handleUp(down.copy(id = trackedPointer.id), size)
            }
        }
        trackedOutputPointer.value?.let { trackedPointer ->
            handleUp(down.copy(id = trackedPointer.id), size)
        }

        val downLayerId = imeController.snapshotState().touchLayerId
        val downKey = touchKeyboard.findKey(downLayerId, down.position.normalized(size)) ?: return

        val timingOptions = interactionController.activeTimingOptions.value
        val keyRepeatTimeout = timingOptions.getKeyRepeatTimeout(downKey.attrs.output)
        val keyRepeatDelay = timingOptions.getKeyRepeatDelay(downKey.attrs.output)
        val longPressTimeout = timingOptions.getLongPressTimeout(downKey.attrs.output)
        val doubleTapTimeout = timingOptions.doubleTapTimeout

        val peekLayerId = downKey.attrs.layerId
        if (peekLayerId != null) {
            trackedMultiTapSeq.value?.let { multiTapSeq ->
                multiTapSeq.commit()
            }
            val downInputShiftState: InputShiftState
            imeController.updateState {
                downInputShiftState = state.flags.inputShiftState
                if (downKey.isShiftKey) {
                    val isDoubleTap = lastUpKey != null && lastUpKey.touchKey.isShiftKey &&
                        (down.uptimeMillis - lastUpKey.uptimeMillis) < doubleTapTimeout.inWholeMilliseconds
                    cycleInputShiftState(isDoubleTap)
                } else {
                    switchTouchLayer(peekLayerId)
                }
            }
            trackedPeekPointer.value = TrackedPointer.Peek(
                id = down.id,
                down = down,
                downKey = downKey,
                downLayerId = downLayerId,
                downInputShiftState = downInputShiftState,
                peekLayerId = peekLayerId,
                peekKey = null,
                peekLine = null,
                peekMustSwitchBack = false,
            )
            interactionController.performFeedback(InteractionKind.KeyPress)
        } else {
            trackedOutputPointer.value = TrackedPointer.Output(
                id = down.id,
                down = down,
                downKey = downKey,
                longPress = if (downKey.isSuitableForPopup) {
                    val anchorBounds = downKey.bounds.let { bounds ->
                        val w = 0.1f
                        val h = bounds.height * 0.9f
                        val x = bounds.bottomCenter.x - w / 2f
                        val y = bounds.bottom - h * 2.3f
                        Rect(
                            offset = Offset(x, y),
                            size = Size(w, h),
                        )
                    }
                    LongPress(
                        anchorBounds = anchorBounds,
                        simpleBounds = if (showSimplePopups && downKey.isSuitableForSimplePopup) {
                            anchorBounds.copy(
                                bottom = anchorBounds.bottom + anchorBounds.height * 1.2f,
                            )
                        } else Rect.Zero,
                        simpleLabel = downKey.display,
                        simpleIndicateExtended = downKey.isSuitableForExtendedPopup,
                        extendedBounds = Rect.Zero,
                        extendedKeys = downKey.extendedPopupKeys,
                        extendedFocusedIndex = 0,
                        extendedJob = if (downKey.isSuitableForExtendedPopup) {
                            launchExtendedLongPressJob(longPressTimeout)
                        } else null,
                    )
                } else LongPress.None,
                keyActionJob = if (downKey.isSpaceBarKey() || downKey.isDeleteKey()) {
                    launchKeyLongPressJob(downKey, longPressTimeout)
                } else null,
                repeatJob = if (downKey.isRepeatable) {
                    scope.launch {
                        delay(keyRepeatTimeout)
                        while (isActive) {
                            imeController.updateState {
                                downKey.attrs.output?.let { emit(it) }
                            }
                            interactionController.performFeedback(InteractionKind.KeyRepeat)
                            delay(keyRepeatDelay)
                        }
                    }
                } else null,
            )
            if (gestureListener != null) {
                gestureRecognizer.onDown(
                    pointerId = down.id.value,
                    x = down.position.x,
                    y = down.position.y,
                    timeMillis = down.uptimeMillis,
                    config = gestureConfig,
                    keyWidthPx = downKey.bounds.width * size.width,
                    glideEligible = downLayerId.isGlideLayer() && downKey.isGlideEligible(),
                    swipeEligible = true,
                )
            }
            trackedMultiTapSeq.value.let { multiTapSeq ->
                if (multiTapSeq != null) {
                    if (multiTapSeq.touchKey !== downKey) {
                        multiTapSeq.commit()
                    } else if (downKey.multiTapKeys.isNotEmpty()) {
                        multiTapSeq.cycleNext()
                    }
                } else if (downKey.multiTapKeys.isNotEmpty()) {
                    val commit = suspend commit@{
                        val multiTapSeq = trackedMultiTapSeq.value ?: return@commit
                        val multiTapKey = multiTapSeq.touchKey.multiTapKeys.getOrNull(multiTapSeq.multiTapIndex)
                            ?: return@commit
                        val output = multiTapKey.data.output ?: return@commit
                        imeController.updateState {
                            emit(output)
                        }
                        trackedMultiTapSeq.value = null
                    }
                    val cycleNext = suspend cycleNext@{
                        val multiTapSeq = trackedMultiTapSeq.value ?: return@cycleNext
                        multiTapSeq.commitJob?.cancel()
                        val newIndex = (multiTapSeq.multiTapIndex + 1) % multiTapSeq.touchKey.multiTapKeys.size.fastCoerceAtLeast(1)
                        trackedMultiTapSeq.value = multiTapSeq.copy(
                            multiTapIndex = newIndex,
                            commitJob = null,
                        )
                    }
                    trackedMultiTapSeq.value = TrackedMultiTapSeq(
                        touchKey = downKey,
                        multiTapIndex = 0,
                        commitJob = null,
                        commit = commit,
                        cycleNext = cycleNext,
                    )
                }
            }
            interactionController.performFeedback(InteractionKind.KeyPress)
        }
    }

    suspend fun handleMove(move: PointerInputChange, size: IntSize) {
        trackedPeekPointer.value?.takeIf { it.id == move.id }?.let { trackedPointer ->
            val distanceSq = (move.position - trackedPointer.down.position).getDistanceSquared()
            if (trackedPointer.peekLine != null || distanceSq >= peekDistanceSqMin) {
                val newPeekKey = touchKeyboard.findKey(trackedPointer.peekLayerId, move.position.normalized(size))
                trackedPeekPointer.value = trackedPointer.copy(
                    peekKey = newPeekKey,
                    peekLine = PeekLine(trackedPointer.down.position, move.position),
                    peekMustSwitchBack = true,
                )
            }
        }
        trackedOutputPointer.value?.takeIf { it.id == move.id }?.let { trackedPointer ->
            val listener = gestureListener
            if (listener != null) {
                gestureRecognizer.onMove(move.id.value, move.position.x, move.position.y, move.uptimeMillis)
                    .forEach { signal ->
                        when (signal) {
                            is GestureSignal.GlideStarted -> listener.onGlideStart()
                            is GestureSignal.GlidePoint -> {
                                listener.onGlidePoint(
                                    normalizeX(signal.x, size),
                                    normalizeY(signal.y, size),
                                )
                            }
                        }
                    }
                if (gestureRecognizer.isGliding(move.id.value)) {
                    if (trackedPointer.longPress !== LongPress.None) {
                        trackedPointer.longPress.extendedJob?.cancel()
                        trackedOutputPointer.value = trackedPointer.copy(longPress = LongPress.None)
                    }
                    return@let
                }
            }
            val longPress = trackedPointer.longPress
            if (longPress.shouldShowExtendedPopup()) {
                val position = move.position.normalized(size).let { position ->
                    Offset(
                        x = (position.x)
                            .fastCoerceIn(longPress.extendedBounds.left, longPress.extendedBounds.right),
                        y = (position.y - longPress.anchorBounds.height)
                            .fastCoerceIn(longPress.extendedBounds.top, longPress.extendedBounds.bottom - 0.01f),
                    )
                }
                val newFocusedIndex = longPress.getNearestKeyIndex(position)
                if (newFocusedIndex != longPress.extendedFocusedIndex) {
                    trackedOutputPointer.value = trackedPointer.copy(
                        longPress = longPress.copy(extendedFocusedIndex = newFocusedIndex),
                    )
                }
            }
        }
    }

    context(scope: CoroutineScope)
    suspend fun handleUp(up: PointerInputChange, size: IntSize) {
        trackedPeekPointer.value?.takeIf { it.id == up.id }?.let { trackedPointer ->
            imeController.updateState {
                trackedPointer.peekKey?.attrs?.output?.let { emit(it) }
                if (trackedPointer.peekMustSwitchBack) {
                    if (trackedPointer.downKey.isShiftKey) {
                        revertCycleInputShiftState(
                            trackedPointer.downLayerId, trackedPointer.downInputShiftState
                        )
                    } else {
                        switchTouchLayer(trackedPointer.downLayerId)
                    }
                }
            }
            trackedPeekPointer.value = null
            lastUpKeyState = LastUpKey(trackedPointer.downKey, up.uptimeMillis)
        }
        trackedOutputPointer.value?.takeIf { it.id == up.id }?.let { trackedPointer ->
            trackedPointer.longPress.extendedJob?.cancel()
            trackedPointer.repeatJob?.cancel()
            trackedPointer.keyActionJob?.cancel()

            val wasGliding = gestureRecognizer.isGliding(up.id.value)
            val gestureOutcome = gestureRecognizer.onUp(up.id.value, up.position.x, up.position.y, up.uptimeMillis)
            val isTap = gestureOutcome == GestureOutcome.Tap
            if (!isTap) {
                cancelMultiTap()
            }

            imeController.updateState {
                val output = when {
                    !isTap -> null
                    trackedPointer.downKey == trackedMultiTapSeq.value?.touchKey -> null
                    trackedPointer.keyActionConsumed -> null
                    trackedPointer.longPress.shouldShowExtendedPopup() -> {
                        trackedPointer.longPress.extendedKeys.getOrNull(trackedPointer.longPress.extendedFocusedIndex)
                            ?.data?.output
                    }
                    else -> trackedPointer.downKey.attrs.output
                }
                output?.let { emit(it) }
            }
            when (gestureOutcome) {
                is GestureOutcome.Glide -> gestureListener?.let { listener ->
                    if (!wasGliding) listener.onGlideStart()
                    listener.onGlideComplete(gestureOutcome.points.map { it.normalized(size) })
                }
                is GestureOutcome.Swipe -> gestureListener?.onSwipe(
                    gestureOutcome.direction,
                    gestureOutcome.unitCountX,
                    gestureOutcome.unitCountY,
                    trackedPointer.downKey,
                )
                GestureOutcome.Tap -> Unit
            }
            trackedPeekPointer.update { it?.copy(peekMustSwitchBack = true) }
            trackedOutputPointer.value = null
            lastUpKeyState = LastUpKey(trackedPointer.downKey, up.uptimeMillis)
        }
        trackedMultiTapSeq.update { multiTapSeq ->
            if (multiTapSeq != null) {
                multiTapSeq.commitJob?.cancel()
                multiTapSeq.copy(
                    commitJob = scope.launch {
                        val timingOptions = interactionController.activeTimingOptions.value
                        delay(timingOptions.multiTapTimeout)
                        mutateLocked {
                            if (trackedOutputPointer.value?.longPress?.extendedBounds?.isEmpty == false) {
                                return@mutateLocked
                            }
                            multiTapSeq.commit()
                        }
                    }
                )
            } else null
        }
    }

    suspend fun cancelNonPresent(changes: List<PointerInputChange>) {
        trackedPeekPointer.value?.id?.let { id ->
            if (changes.fastAll { it.id != id }) {
                cancelPeek()
            }
        }
        trackedOutputPointer.value?.id?.let { id ->
            if (changes.fastAll { it.id != id }) {
                cancelOutput()
            }
        }
    }

    suspend fun cancelAll() {
        cancelPeek()
        cancelOutput()
        gestureRecognizer.clear()
    }

    private suspend fun cancelPeek() {
        val trackedPointer = trackedPeekPointer.getAndUpdate { null } ?: return
        imeController.updateState {
            if (trackedPointer.downKey.isShiftKey) {
                revertCycleInputShiftState(
                    trackedPointer.downLayerId, trackedPointer.downInputShiftState
                )
            } else {
                switchTouchLayer(trackedPointer.downLayerId)
            }
        }
    }

    private suspend fun cancelOutput() {
        cancelMultiTap()
        val trackedPointer = trackedOutputPointer.getAndUpdate { null } ?: return
        trackedPointer.longPress.extendedJob?.cancel()
        trackedPointer.repeatJob?.cancel()
        trackedPointer.keyActionJob?.cancel()
        if (gestureRecognizer.onCancel(trackedPointer.id.value)) {
            gestureListener?.onGlideCancel()
        }
    }

    private suspend fun cancelMultiTap() {
        val multiTapSeq = trackedMultiTapSeq.getAndUpdate { null } ?: return
        multiTapSeq.commitJob?.cancel()
    }

    private fun Offset.normalized(size: IntSize): Offset {
        return Offset(x / size.width, y / size.height)
    }

    context(scope: CoroutineScope)
    private fun launchKeyLongPressJob(
        downKey: TouchKey,
        longPressTimeout: Duration,
    ): Job = scope.launch(Dispatchers.Default) {
        delay(longPressTimeout)
        mutateLocked {
            val trackedPointer = trackedOutputPointer.value ?: return@mutateLocked
            if (trackedPointer.downKey != downKey) return@mutateLocked
            if (gestureRecognizer.isGliding(trackedPointer.id.value)) return@mutateLocked
            if (gestureListener?.onKeyLongPress(downKey) == true) {
                trackedOutputPointer.value = trackedPointer.copy(keyActionConsumed = true)
                interactionController.performFeedback(InteractionKind.LongPress)
            }
        }
    }

    context(scope: CoroutineScope)
    private fun launchExtendedLongPressJob(
        longPressTimeout: Duration,
    ): Job = scope.launch(Dispatchers.Default) {
        delay(longPressTimeout)
        mutateLocked {
            trackedMultiTapSeq.value?.let { multiTapSeq ->
                multiTapSeq.commitJob?.cancel()
                trackedMultiTapSeq.value = null
            }
            val trackedPointer = trackedOutputPointer.value ?: return@mutateLocked
            if (gestureRecognizer.isGliding(trackedPointer.id.value)) return@mutateLocked
            val longPress = trackedPointer.longPress
            val anchorBounds = longPress.anchorBounds
            val extendedKeys = layoutExtendedLongPressKeys(longPress.extendedKeys, anchorBounds)
            val extendedBounds = anchorBounds.copy(
                left = extendedKeys.fastFold(anchorBounds.left) { acc, key -> minOf(acc, key.bounds.left) },
                top = extendedKeys.fastFold(anchorBounds.top) { acc, key -> minOf(acc, key.bounds.top) },
                right = extendedKeys.fastFold(anchorBounds.right) { acc, key -> maxOf(acc, key.bounds.right) },
                bottom = extendedKeys.fastFold(anchorBounds.bottom) { acc, key -> maxOf(acc, key.bounds.bottom) },
            )
            trackedOutputPointer.value = trackedPointer.copy(
                longPress = trackedPointer.longPress.copy(
                    simpleIndicateExtended = false,
                    extendedBounds = extendedBounds,
                    extendedKeys = extendedKeys,
                ),
            )
        }
    }
}

private val ASCII_SPACE = " ".asK3String()

private fun normalizeX(x: Float, size: IntSize): Float = if (size.width == 0) 0f else x / size.width

private fun normalizeY(y: Float, size: IntSize): Float = if (size.height == 0) 0f else y / size.height

private fun GesturePoint.normalized(size: IntSize): GesturePoint {
    return copy(x = normalizeX(x, size), y = normalizeY(y, size))
}

private fun K3LayerId.isGlideLayer(): Boolean {
    return this == ImeLayerIds.Base || this == ImeLayerIds.Shift || this == ImeLayerIds.Caps
}

private fun TouchKey.isGlideEligible(): Boolean {
    if (isShiftKey) {
        return false
    }
    return when (val output = attrs.output) {
        null -> false
        is K3String -> output != ASCII_SPACE
        is K3Descriptor -> output != ImeActions.Backspace &&
            output != ImeActions.Delete &&
            output != ImeActions.DeleteWord
    }
}

@Composable
fun rememberPointerTracker(
    touchKeyboard: TouchKeyboard,
    gestureListener: GestureListener? = null,
): PointerTracker {
    val prefs by DriftPreferenceStore
    val density = LocalDensity.current
    val imeController = LocalImeController.current
    val interactionController = LocalInteractionController.current

    val showSimplePopups by prefs.keyboard.popupEnabled.collectAsState()
    val swipeDistanceDp by prefs.gestures.swipeDistanceThreshold.collectAsState()
    val swipeVelocityDpPerS by prefs.gestures.swipeVelocityThreshold.collectAsState()
    val glideEnabled by prefs.glide.enabled.collectAsState()
    val peekDistanceSqMin = with(density) { 30.dp.toPx().pow(2) }
    val gestureConfig = remember(density, swipeDistanceDp, swipeVelocityDpPerS, glideEnabled) {
        GestureConfig(
            swipeDistancePx = with(density) { swipeDistanceDp.dp.toPx() },
            swipeVelocityPxPerMs = with(density) { swipeVelocityDpPerS.dp.toPx() } / 1000f,
            glideEnabled = glideEnabled,
            glideMinVelocityPxPerMs = with(density) { 0.10f.dp.toPx() },
            glideMaxDurationMillis = 500L,
        )
    }

    return remember(touchKeyboard, showSimplePopups, gestureConfig, gestureListener) {
        PointerTracker(
            touchKeyboard = touchKeyboard,
            imeController = imeController,
            interactionController = interactionController,
            showSimplePopups = showSimplePopups,
            peekDistanceSqMin = peekDistanceSqMin,
            gestureConfig = gestureConfig,
            gestureListener = gestureListener,
        )
    }
}

private enum class PointerChangeKind {
    DOWN,
    UP,
    MOVE,
}

private class PointerChangeItem(
    val change: PointerInputChange,
    val kind: PointerChangeKind,
    val size: IntSize,
)

private class PointerEventBatch(
    val changes: List<PointerInputChange>,
    val items: List<PointerChangeItem>,
)

fun Modifier.trackPointerInput(
    pointerTracker: PointerTracker,
    model: K3Model,
) = this.pointerInput(pointerTracker, model) {
    try {
        coroutineScope {
            val eventChannel = Channel<PointerEventBatch>(Channel.UNLIMITED)
            launch {
                for (batch in eventChannel) {
                    pointerTracker.mutateLocked {
                        cancelNonPresent(batch.changes)
                        batch.items.fastForEach { item ->
                            when (item.kind) {
                                PointerChangeKind.DOWN -> handleDown(item.change, item.size)
                                PointerChangeKind.UP -> handleUp(item.change, item.size)
                                PointerChangeKind.MOVE -> handleMove(item.change, item.size)
                            }
                        }
                    }
                }
            }
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    // Classify before consuming: the consumer runs after this block and would
                    // otherwise observe every change as consumed.
                    val batch = PointerEventBatch(
                        changes = event.changes,
                        items = event.changes.mapNotNull { change ->
                            val kind = when {
                                change.changedToDown() -> PointerChangeKind.DOWN
                                change.changedToUp() -> PointerChangeKind.UP
                                !change.isConsumed -> PointerChangeKind.MOVE
                                else -> null
                            }
                            kind?.let { PointerChangeItem(change, it, size) }
                        },
                    )
                    eventChannel.trySend(batch)
                    event.changes.fastForEach { change ->
                        change.consume()
                    }
                }
            }
        }
    } finally {
        withContext(NonCancellable) {
            pointerTracker.mutateLocked {
                cancelAll()
            }
        }
    }
}
