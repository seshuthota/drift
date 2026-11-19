/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
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

package io.github.seshuthota.drift.lib.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object DriftCardDefaults {
    val IconRequiredSize = 24.dp
    val IconSpacing = 12.dp

    val ContentPadding = PaddingValues(start = 0.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
}

object BoxDefaults {
    val OutlinedBoxShape = RoundedCornerShape(8.dp)

    val ContentPadding = PaddingValues(all = 0.dp)
}

@Composable
fun DriftSimpleCard(
    modifier: Modifier = Modifier,
    text: String,
    secondaryText: String? = null,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    contentPadding: PaddingValues = DriftCardDefaults.ContentPadding,
    icon: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Card(
        onClick = onClick ?: { },
        enabled = onClick != null,
        modifier = modifier
            .padding(vertical = 8.dp)
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            contentColor = contentColor,
            containerColor = backgroundColor,
            disabledContainerColor = backgroundColor,
            disabledContentColor = contentColor,
        )
    ) {
        Row(
            modifier = Modifier.padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                icon()
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (icon == null) 16.dp else 0.dp),
            ) {
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = text,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (secondaryText != null) {
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = secondaryText,
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        }
    }
}

@Composable
fun DriftErrorCard(
    text: String,
    modifier: Modifier = Modifier,
    showIcon: Boolean = true,
    contentPadding: PaddingValues = DriftCardDefaults.ContentPadding,
    onClick: (() -> Unit)? = null,
) {
    DriftSimpleCard(
        modifier = modifier,
        backgroundColor = Color.Red,
        contentColor = Color.White,
        onClick = onClick,
        icon = if (showIcon) ({ Icon(
            modifier = Modifier
                .padding(all = DriftCardDefaults.IconSpacing)
                .requiredSize(DriftCardDefaults.IconRequiredSize),
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = null,
        ) }) else null,
        text = text,
        contentPadding = contentPadding,
    )
}

@Composable
fun DriftWarningCard(
    text: String,
    modifier: Modifier = Modifier,
    showIcon: Boolean = true,
    contentPadding: PaddingValues = DriftCardDefaults.ContentPadding,
    onClick: (() -> Unit)? = null,
) {
    DriftSimpleCard(
        modifier = modifier,
        backgroundColor = Color.Yellow,
        contentColor = Color.Black,
        onClick = onClick,
        icon = if (showIcon) ({ Icon(
            modifier = Modifier
                .padding(all = DriftCardDefaults.IconSpacing)
                .requiredSize(DriftCardDefaults.IconRequiredSize),
            imageVector = Icons.Outlined.Warning,
            contentDescription = null,
        ) }) else null,
        text = text,
        contentPadding = contentPadding,
    )
}

@Composable
fun DriftInfoCard(
    text: String,
    modifier: Modifier = Modifier,
    showIcon: Boolean = true,
    contentPadding: PaddingValues = DriftCardDefaults.ContentPadding,
    onClick: (() -> Unit)? = null,
) {
    DriftSimpleCard(
        modifier = modifier,
        onClick = onClick,
        icon = if (showIcon) ({ Icon(
            modifier = Modifier
                .padding(all = DriftCardDefaults.IconSpacing)
                .requiredSize(DriftCardDefaults.IconRequiredSize),
            imageVector = Icons.Default.Info,
            contentDescription = null,
        ) }) else null,
        text = text,
        contentPadding = contentPadding,
    )
}

@Composable
fun DriftOutlinedBox(
    modifier: Modifier = Modifier,
    title: (@Composable () -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    subtitle: (@Composable () -> Unit)? = null,
    onSubtitleClick: (() -> Unit)? = null,
    borderWidth: Dp = 1.dp,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
    shape: Shape = BoxDefaults.OutlinedBoxShape,
    contentPadding: PaddingValues = BoxDefaults.ContentPadding,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .padding(top = if (title != null) 11.dp else 0.dp),
    ) {
        Column(
            modifier = Modifier
                .border(borderWidth, borderColor, shape)
                .clip(shape)
                .padding(top = if (title != null) 11.dp else 0.dp),
        ) {
            if (title != null && subtitle != null) {
                Box(
                    modifier = Modifier
                        .padding(start = 10.dp, bottom = 4.dp)
                        .rippleClickable(enabled = onSubtitleClick != null) {
                            onSubtitleClick!!()
                        },
                ) {
                    subtitle()
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(contentPadding),
                content = content,
            )
        }
        if (title != null) {
            Box(
                modifier = Modifier
                    .height(23.dp)
                    .offset(x = 10.dp, y = (-12).dp)
                    .background(MaterialTheme.colorScheme.background)
                    .rippleClickable(enabled = onTitleClick != null) {
                        onTitleClick!!()
                    }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                title()
            }
        }
    }
}

fun Modifier.defaultDriftOutlinedBox(): Modifier {
    return this
        .fillMaxWidth()
        .padding(vertical = 8.dp)
}
