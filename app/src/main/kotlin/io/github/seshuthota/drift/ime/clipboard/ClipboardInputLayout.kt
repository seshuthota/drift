/*
 * Copyright (C) 2021-2026 The FlorisBoard Contributors
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

package io.github.seshuthota.drift.ime.clipboard

import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.provider.MediaStore
import android.util.Size
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FilterListOff
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.ToggleOff
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.ContentPasteGo
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.seshuthota.drift.R
import io.github.seshuthota.drift.app.DriftPreferenceStore
import io.github.seshuthota.drift.clipboardManager
import io.github.seshuthota.drift.ime.ImeUiMode
import io.github.seshuthota.drift.ime.clipboard.provider.ClipboardFileStorage
import io.github.seshuthota.drift.ime.clipboard.provider.ClipboardItem
import io.github.seshuthota.drift.ime.clipboard.provider.ItemType
import io.github.seshuthota.drift.ime.keyboard.DriftImeSizing
import io.github.seshuthota.drift.ime.keyboard3.ImeActions
import io.github.seshuthota.drift.ime.keyboard3.LocalImeController
import io.github.seshuthota.drift.ime.keyboard3.interaction.LocalInteractionController
import io.github.seshuthota.drift.ime.keyboard3.interaction.showShortToast
import io.github.seshuthota.drift.ime.keyboard3.ui.ImeKeyButton
import io.github.seshuthota.drift.ime.smartbar.AnimationDuration
import io.github.seshuthota.drift.ime.smartbar.VerticalEnterTransition
import io.github.seshuthota.drift.ime.smartbar.VerticalExitTransition
import io.github.seshuthota.drift.ime.theme.DriftImeUi
import io.github.seshuthota.drift.ime.theme.ImeUiAttrs
import io.github.seshuthota.drift.lib.observeAsTransformingState
import io.github.seshuthota.drift.lib.util.NetworkUtils
import dev.patrickgold.jetpref.datastore.model.collectAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.seshuthota.drift.lib.android.isOrientationPortrait
import io.github.seshuthota.drift.lib.android.AndroidKeyguardManager
import io.github.seshuthota.drift.lib.android.AndroidVersion
import io.github.seshuthota.drift.lib.android.systemService
import io.github.seshuthota.drift.lib.compose.LocalLocalizedDateTimeFormatter
import io.github.seshuthota.drift.lib.compose.autoMirrorForRtl
import io.github.seshuthota.drift.lib.compose.driftHorizontalScroll
import io.github.seshuthota.drift.lib.compose.driftVerticalScroll
import io.github.seshuthota.drift.lib.compose.rippleClickable
import io.github.seshuthota.drift.lib.compose.stringRes
import io.github.seshuthota.drift.lib.snygg.SnyggQueryAttributes
import io.github.seshuthota.drift.lib.snygg.ui.SnyggBox
import io.github.seshuthota.drift.lib.snygg.ui.SnyggButton
import io.github.seshuthota.drift.lib.snygg.ui.SnyggChip
import io.github.seshuthota.drift.lib.snygg.ui.SnyggColumn
import io.github.seshuthota.drift.lib.snygg.ui.SnyggIcon
import io.github.seshuthota.drift.lib.snygg.ui.SnyggIconButton
import io.github.seshuthota.drift.lib.snygg.ui.SnyggRow
import io.github.seshuthota.drift.lib.snygg.ui.SnyggText
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

private val ItemWidth = 200.dp
private val DialogWidth = 240.dp

const val CLIPBOARD_HISTORY_NUM_GRID_COLUMNS_AUTO: Int = 0

@Composable
fun ClipboardInputLayout(
    modifier: Modifier = Modifier,
) {
    val prefs by DriftPreferenceStore
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboardManager by context.clipboardManager()
    val imeController = LocalImeController.current
    val interactionController = LocalInteractionController.current
    val androidKeyguardManager = remember { context.systemService(AndroidKeyguardManager::class) }

    val deviceLocked = androidKeyguardManager.let { it.isDeviceLocked || it.isKeyguardLocked }
    val historyEnabled by prefs.clipboard.historyEnabled.collectAsState()

    var isFilterRowShown by remember { mutableStateOf(false) }
    val activeFilterTypes = remember { mutableStateSetOf<ItemType>() }

    val unfilteredHistory by clipboardManager.historyFlow.collectAsState()
    val filteredHistory = remember(unfilteredHistory, activeFilterTypes.toSet()) {
        if (activeFilterTypes.isEmpty()) {
            unfilteredHistory
        } else {
            unfilteredHistory.all
                .filter { activeFilterTypes.contains(it.type) }
                .let { ClipboardHistory(it) }
        }
    }

    val gridState = rememberLazyStaggeredGridState()
    var popupItem by remember(filteredHistory) { mutableStateOf<ClipboardItem?>(null) }
    var showClearAllHistory by remember { mutableStateOf(false) }

    fun isPopupSurfaceActive() = popupItem != null || showClearAllHistory

    LaunchedEffect(isFilterRowShown) {
        delay(AnimationDuration.milliseconds)
        if (!isFilterRowShown) {
            activeFilterTypes.clear()
        }
    }

    LaunchedEffect(activeFilterTypes.toSet()) {
        gridState.scrollToItem(0)
    }

    @Composable
    fun HeaderRow() {
        SnyggRow(DriftImeUi.ClipboardHeader.elementName,
            modifier = Modifier
                .fillMaxWidth()
                .height(DriftImeSizing.smartbarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val sizeModifier = Modifier
                .sizeIn(maxHeight = DriftImeSizing.smartbarHeight)
                .aspectRatio(1f)
            SnyggIconButton(
                elementName = DriftImeUi.ClipboardHeaderButton.elementName,
                onClick = {
                    imeController.updateStateBlocking {
                        state = state.copy(
                            flags = state.flags.withImeUiMode(ImeUiMode.TEXT),
                        )
                    }
                },
                modifier = sizeModifier,
            ) {
                SnyggIcon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                )
            }
            SnyggText(
                elementName = DriftImeUi.ClipboardHeaderText.elementName,
                modifier = Modifier.weight(1f),
                text = stringRes(R.string.clipboard__header_title),
            )
            SnyggIconButton(
                elementName = DriftImeUi.ClipboardHeaderButton.elementName,
                onClick = { scope.launch { prefs.clipboard.historyEnabled.set(!historyEnabled) } },
                modifier = sizeModifier.autoMirrorForRtl(),
                enabled = !deviceLocked && !isPopupSurfaceActive(),
            ) {
                SnyggIcon(
                    imageVector = if (historyEnabled) {
                        Icons.Default.ToggleOn
                    } else {
                        Icons.Default.ToggleOff
                    },
                )
            }
            SnyggIconButton(
                elementName = DriftImeUi.ClipboardHeaderButton.elementName,
                onClick = { showClearAllHistory = true },
                modifier = sizeModifier.autoMirrorForRtl(),
                enabled = !deviceLocked && historyEnabled && filteredHistory.all.isNotEmpty() && !isPopupSurfaceActive(),
            ) {
                SnyggIcon(
                    imageVector = Icons.Default.DeleteSweep,
                )
            }
            SnyggIconButton(
                elementName = DriftImeUi.ClipboardHeaderButton.elementName,
                onClick = { isFilterRowShown = !isFilterRowShown },
                modifier = sizeModifier,
                enabled = !deviceLocked && historyEnabled && unfilteredHistory.all.isNotEmpty() && !isPopupSurfaceActive(),
            ) {
                SnyggIcon(
                    imageVector = if (!isFilterRowShown) {
                        Icons.Default.FilterList
                    } else {
                        Icons.Default.FilterListOff
                    },
                )
            }
            ImeKeyButton(
                elementName = DriftImeUi.ClipboardHeaderButton.elementName,
                output = ImeActions.Backspace,
                modifier = sizeModifier,
            )
        }
    }

    @Composable
    fun ClipItemView(
        elementName: String,
        item: ClipboardItem,
        contentScrollInsteadOfClip: Boolean,
        modifier: Modifier = Modifier,
    ) {
        val attributes = remember(item.type) {
            mapOf(
                ImeUiAttrs.ClipboardItemType.eq(item.type),
            )
        }
        SnyggBox(
            elementName = elementName,
            attributes = attributes,
            modifier = modifier.fillMaxWidth(),
            clickAndSemanticsModifier = Modifier.combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                enabled = popupItem == null,
                onLongClick = {
                    popupItem = item
                },
                onClick = {
                    clipboardManager.pasteItem(item)
                },
            ),
        ) {
            if (item.type == ItemType.IMAGE) {
                val bitmap = remember(item.uri) {
                    runCatching {
                        val id = ContentUris.parseId(checkNotNull(item.uri) { "Image clip has no uri" })
                        val file = ClipboardFileStorage.getFileForId(context, id)
                        check(file.exists()) { "Unable to resolve image at ${file.absolutePath}" }
                        val rawBitmap = decodeThumbnail(file.absolutePath, THUMBNAIL_MAX_WIDTH_PX)
                        checkNotNull(rawBitmap) { "Unable to decode image at ${file.absolutePath}" }
                        rawBitmap.asImageBitmap()
                    }
                }
                if (bitmap.isSuccess) {
                    Image(
                        modifier = Modifier.fillMaxWidth(),
                        bitmap = bitmap.getOrThrow(),
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                    )
                } else {
                    SnyggText(
                        modifier = Modifier.fillMaxWidth(),
                        text = bitmap.exceptionOrNull()?.message ?: "Unknown error",
                    )
                }
            } else if (item.type == ItemType.VIDEO) {
                val bitmap = remember(item.uri) {
                    runCatching {
                        val id = ContentUris.parseId(checkNotNull(item.uri) { "Video clip has no uri" })
                        val file = ClipboardFileStorage.getFileForId(context, id)
                        check(file.exists()) { "Unable to resolve video at ${file.absolutePath}" }
                        val rawBitmap = if (AndroidVersion.ATLEAST_API29_Q) {
                            val dataRetriever = MediaMetadataRetriever()
                            dataRetriever.setDataSource(file.absolutePath)
                            val width = dataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                            val height = dataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                            dataRetriever.release()
                            val videoWidth = checkNotNull(width?.toIntOrNull()) { "Video has no width" }
                            val videoHeight = checkNotNull(height?.toIntOrNull()) { "Video has no height" }
                            val scale = minOf(1f, THUMBNAIL_MAX_WIDTH_PX.toFloat() / videoWidth)
                            val size = Size(
                                (videoWidth * scale).toInt().coerceAtLeast(1),
                                (videoHeight * scale).toInt().coerceAtLeast(1),
                            )
                            ThumbnailUtils.createVideoThumbnail(file, size, null)
                        } else {
                            @Suppress("DEPRECATION")
                            ThumbnailUtils.createVideoThumbnail(file.absolutePath, MediaStore.Video.Thumbnails.MINI_KIND)
                        }
                        checkNotNull(rawBitmap) { "Unable to decode video at ${file.absolutePath}" }
                        rawBitmap.asImageBitmap()
                    }
                }
                if (bitmap.isSuccess) {
                    Image(
                        modifier = Modifier.fillMaxWidth(),
                        bitmap = bitmap.getOrThrow(),
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                    )
                    Icon(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 4.dp, bottom = 4.dp)
                            .background(Color.White, CircleShape),
                        imageVector = Icons.Default.Videocam,
                        contentDescription = null,
                        tint = Color.Black,
                    )
                } else {
                    SnyggText(
                        modifier = Modifier.fillMaxWidth(),
                        text = bitmap.exceptionOrNull()?.message ?: "Unknown error",
                    )
                }
            } else {
                val text = item.stringRepresentation()
                Column {
                    ClipTextItemDescription(
                        elementName = DriftImeUi.ClipboardItemDescription.elementName,
                        attributes = attributes,
                        text = text,
                    )
                    SnyggText(
                        modifier = Modifier
                            .fillMaxWidth()
                            .run { if (contentScrollInsteadOfClip) this.driftVerticalScroll() else this },
                        text = item.displayText(),
                    )
                }
            }
        }
    }

    @Composable
    fun HistoryMainView() {
        SnyggBox(DriftImeUi.ClipboardContent.elementName,
            modifier = Modifier.fillMaxSize(),
        ) {
            val historyAlpha by animateFloatAsState(targetValue = if (isPopupSurfaceActive()) 0.12f else 1f)
            // Chosen here rather than by a @Composable on the preference model: callers of a composable member read
            // its class's Compose stability field, and an incremental release build once dropped that field from
            // DriftPreferenceModel.Clipboard, crashing the keyboard whenever the clipboard panel opened.
            val gridColumnsPref = if (LocalConfiguration.current.isOrientationPortrait()) {
                prefs.clipboard.historyNumGridColumnsPortrait
            } else {
                prefs.clipboard.historyNumGridColumnsLandscape
            }
            val staggeredGridCells by gridColumnsPref
                .observeAsTransformingState { numGridColumns ->
                    if (numGridColumns == CLIPBOARD_HISTORY_NUM_GRID_COLUMNS_AUTO) {
                        StaggeredGridCells.Adaptive(160.dp)
                    } else {
                        StaggeredGridCells.Fixed(numGridColumns)
                    }
                }

            fun LazyStaggeredGridScope.clipboardItems(
                items: List<ClipboardItem>,
                key: String,
                @StringRes title: Int,
            ) {
                if (items.isNotEmpty()) {
                    item(key, span = StaggeredGridItemSpan.FullLine) {
                        ClipCategoryTitle(text = stringRes(title))
                    }
                    items(items) { item ->
                        ClipItemView(
                            elementName = DriftImeUi.ClipboardItem.elementName,
                            item = item,
                            contentScrollInsteadOfClip = false,
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .matchParentSize()
                    .alpha(historyAlpha),
            ) {
                AnimatedVisibility(
                    visible = isFilterRowShown,
                    enter = VerticalEnterTransition,
                    exit = VerticalExitTransition,
                ) {
                    SnyggRow(
                        elementName = DriftImeUi.ClipboardFilterRow.elementName,
                        modifier = Modifier.fillMaxWidth(),
                        clickAndSemanticsModifier = Modifier.driftHorizontalScroll(),
                    ) {
                        @Composable
                        fun FilterChip(
                            imageVector: ImageVector,
                            text: String,
                            itemType: ItemType,
                        ) {
                            val active = activeFilterTypes.contains(itemType)
                            val attributes = remember(active, itemType) {
                                mapOf(
                                    ImeUiAttrs.ClipboardFilterState.eq(active),
                                    ImeUiAttrs.ClipboardItemType.eq(itemType),
                                )
                            }
                            SnyggChip(
                                elementName = DriftImeUi.ClipboardFilterChip.elementName,
                                attributes = attributes,
                                onClick = {
                                    if (!activeFilterTypes.add(itemType)) {
                                        activeFilterTypes.remove(itemType)
                                    }
                                },
                                imageVector = imageVector,
                                text = text,
                            )
                        }

                        FilterChip(
                            imageVector = Icons.Default.TextFields,
                            text = "Text",
                            itemType = ItemType.TEXT,
                        )
                        FilterChip(
                            imageVector = Icons.Default.Image,
                            text = "Images",
                            itemType = ItemType.IMAGE,
                        )
                        FilterChip(
                            imageVector = Icons.Default.Movie,
                            text = "Videos",
                            itemType = ItemType.VIDEO,
                        )
                    }
                }
                SnyggBox(DriftImeUi.ClipboardGrid.elementName,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    LazyVerticalStaggeredGrid(
                        modifier = Modifier.fillMaxSize(),
                        state = gridState,
                        columns = staggeredGridCells,
                    ) {
                        clipboardItems(
                            items = filteredHistory.pinned,
                            key = "pinned-header",
                            title = R.string.clipboard__group_pinned,
                        )
                        clipboardItems(
                            items = filteredHistory.recent,
                            key = "recent-header",
                            title = R.string.clipboard__group_recent,
                        )
                        clipboardItems(
                            items = filteredHistory.other,
                            key = "other-header",
                            title = R.string.clipboard__group_other,
                        )
                    }
                }
            }

            if (popupItem != null) {
                SnyggRow(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { popupItem = null }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceAround,
                ) {
                    SnyggColumn(modifier = Modifier.weight(0.5f)) {
                        ClipItemView(
                            elementName = DriftImeUi.ClipboardItemPopup.elementName,
                            modifier = Modifier
                                .widthIn(max = ItemWidth)
                                .weight(1f, fill = false),
                            item = popupItem!!,
                            contentScrollInsteadOfClip = true,
                        )
                        SnyggBox(DriftImeUi.ClipboardItemTimestamp.elementName) {
                            val formatter = LocalLocalizedDateTimeFormatter.current
                            SnyggText(
                                modifier = Modifier.fillMaxWidth(),
                                text = formatter.format(Instant.ofEpochMilli(popupItem!!.creationTimestampMs)),
                            )
                        }
                    }
                    SnyggColumn(modifier = Modifier.weight(0.5f)) {
                        SnyggColumn(DriftImeUi.ClipboardItemActions.elementName) {
                            PopupAction(
                                icon = Icons.Outlined.PushPin,
                                text = stringRes(if (popupItem!!.isPinned) {
                                    R.string.clip__unpin_item
                                } else {
                                    R.string.clip__pin_item
                                }),
                            ) {
                                if (popupItem!!.isPinned) {
                                    clipboardManager.unpinClip(popupItem!!)
                                } else {
                                    clipboardManager.pinClip(popupItem!!)
                                }
                                popupItem = null
                            }
                            PopupAction(
                                icon = Icons.Default.Delete,
                                text = stringRes(R.string.clip__delete_item),
                            ) {
                                clipboardManager.deleteClip(popupItem!!, onlyIfUnpinned = false)
                                popupItem = null
                            }
                            PopupAction(
                                icon = Icons.Outlined.ContentPasteGo,
                                text = stringRes(R.string.clip__paste_item),
                            ) {
                                clipboardManager.pasteItem(popupItem!!)
                                popupItem = null
                            }
                        }
                    }
                }
            }

            if (showClearAllHistory) {
                SnyggRow(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { showClearAllHistory = false }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceAround,
                ) {
                    SnyggColumn(
                        elementName = DriftImeUi.ClipboardClearAllDialog.elementName,
                        modifier = Modifier
                            .width(DialogWidth)
                            .pointerInput(Unit) {
                                detectTapGestures { /* Do nothing */ }
                            },
                    ) {
                        SnyggText(
                            elementName = DriftImeUi.ClipboardClearAllDialogMessage.elementName,
                            text = stringRes(
                                if (isFilterRowShown) {
                                    R.string.clipboard__confirm_clear_filtered_history__message
                                } else {
                                    R.string.clipboard__confirm_clear_unfiltered_history__message
                                }
                            ),
                        )
                        SnyggRow(DriftImeUi.ClipboardClearAllDialogButtons.elementName) {
                            Spacer(modifier = Modifier.weight(1f))
                            SnyggButton(
                                elementName = DriftImeUi.ClipboardClearAllDialogButton.elementName,
                                attributes = remember {
                                    mapOf(
                                        ImeUiAttrs.ClipboardDialogAction.eq(false),
                                    )
                                },
                                onClick = {
                                    showClearAllHistory = false
                                },
                            ) {
                                SnyggText(
                                    text = stringRes(R.string.action__no),
                                )
                            }
                            val clearedHistoryMsg = stringRes(R.string.clipboard__cleared_history)
                            SnyggButton(
                                elementName = DriftImeUi.ClipboardClearAllDialogButton.elementName,
                                attributes = remember {
                                    mapOf(
                                        ImeUiAttrs.ClipboardDialogAction.eq(true),
                                    )
                                },
                                onClick = {
                                    scope.launch {
                                        clipboardManager.clearExactHistory(filteredHistory.unpinned)
                                        interactionController.showShortToast(clearedHistoryMsg)
                                    }
                                    showClearAllHistory = false
                                },
                            ) {
                                SnyggText(
                                    text = stringRes(R.string.action__yes),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun HistoryEmptyView() {
        SnyggColumn(DriftImeUi.ClipboardContent.elementName,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SnyggText(
                text = stringRes(R.string.clipboard__empty__title),
            )
            SnyggText(
                text = stringRes(R.string.clipboard__empty__message),
            )
        }
    }

    @Composable
    fun HistoryDisabledView() {
        SnyggColumn(DriftImeUi.ClipboardContent.elementName,
            modifier = Modifier.fillMaxSize(),
        ) {
            SnyggText(
                elementName = DriftImeUi.ClipboardHistoryDisabledTitle.elementName,
                modifier = Modifier.padding(bottom = 8.dp),
                text = stringRes(R.string.clipboard__disabled__title),
            )
            SnyggText(
                elementName = DriftImeUi.ClipboardHistoryDisabledMessage.elementName,
                text = stringRes(R.string.clipboard__disabled__message),
            )
            SnyggButton(DriftImeUi.ClipboardHistoryDisabledButton.elementName,
                onClick = { scope.launch { prefs.clipboard.historyEnabled.set(true) } },
                modifier = Modifier.align(Alignment.End),
            ) {
                SnyggText(
                    text = stringRes(R.string.clipboard__disabled__enable_button),
                )
            }
        }
    }

    @Composable
    fun HistoryLockedView() {
        SnyggColumn(DriftImeUi.ClipboardContent.elementName,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SnyggText(
                elementName = DriftImeUi.ClipboardHistoryLockedTitle.elementName,
                text = stringRes(R.string.clipboard__locked__title),
            )
            SnyggText(
                elementName = DriftImeUi.ClipboardHistoryLockedMessage.elementName,
                text = stringRes(R.string.clipboard__locked__message),
            )
        }
    }

    SnyggColumn(
        modifier = modifier
            .fillMaxWidth()
            .height(DriftImeSizing.imeUiHeight()),
    ) {
        HeaderRow()
        if (deviceLocked) {
            HistoryLockedView()
        } else {
            if (historyEnabled) {
                if (filteredHistory.all.isNotEmpty() || !activeFilterTypes.isEmpty()) {
                    HistoryMainView()
                } else {
                    HistoryEmptyView()
                }
            } else {
                HistoryDisabledView()
            }
        }
    }
}

@Composable
private fun ClipCategoryTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    SnyggText(DriftImeUi.ClipboardSubheader.elementName,
        modifier = modifier.fillMaxWidth(),
        text = text.uppercase(),
    )
}

@Composable
private fun ClipTextItemDescription(
    elementName: String,
    attributes: SnyggQueryAttributes,
    text: String,
    modifier: Modifier = Modifier,
): Unit = with(LocalDensity.current) {
    val icon: ImageVector?
    val description: String?
    when {
        NetworkUtils.isEmailAddress(text) -> {
            icon = Icons.Outlined.Email
            description = stringRes(R.string.clipboard__item_description_email)
        }
        NetworkUtils.isUrl(text) -> {
            icon = Icons.Default.Link
            description = stringRes(R.string.clipboard__item_description_url)
        }
        NetworkUtils.isPhoneNumber(text) -> {
            icon = Icons.Default.Phone
            description = stringRes(R.string.clipboard__item_description_phone)
        }
        else -> {
            icon = null
            description = null
        }
    }
    if (icon != null && description != null) {
        SnyggRow(
            elementName = elementName,
            attributes = attributes,
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SnyggIcon(
                imageVector = icon,
            )
            SnyggText(
                modifier = Modifier.weight(1f),
                text = description,
            )
        }
    }
}

@Composable
private fun PopupAction(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    SnyggRow(DriftImeUi.ClipboardItemAction.elementName,
        modifier = modifier.rippleClickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SnyggIcon(DriftImeUi.ClipboardItemActionIcon.elementName,
            imageVector = icon,
        )
        SnyggText(DriftImeUi.ClipboardItemActionText.elementName,
            modifier = Modifier.weight(1f),
            text = text,
        )
    }
}

/** Clip previews are at most this wide; the history grid never shows them larger than the keyboard. */
private const val THUMBNAIL_MAX_WIDTH_PX = 540

/**
 * Decodes the image at [path] scaled down by a power of two until it is at most [maxWidth] pixels wide and at most
 * 4 million pixels in total. A full-size decode of a screenshot costs ~10 MB, and a scrolling screenshot can exceed
 * the 100 MB a canvas will draw, which throws during drawing and takes the keyboard down.
 */
internal fun decodeThumbnail(path: String, maxWidth: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = thumbnailSampleSize(bounds.outWidth, bounds.outHeight, maxWidth)
    }
    return BitmapFactory.decodeFile(path, options)
}

internal fun thumbnailSampleSize(width: Int, height: Int, maxWidth: Int, maxPixels: Long = 4_000_000L): Int {
    var sample = 1
    while (width / sample > maxWidth || (width / sample).toLong() * (height / sample) > maxPixels) {
        sample *= 2
    }
    return sample
}
