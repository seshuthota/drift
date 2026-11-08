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

package io.github.seshuthota.drift.app.components

import android.app.Activity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.seshuthota.drift.app.DriftPreferenceModel
import io.github.seshuthota.drift.app.DriftPreferenceStore
import io.github.seshuthota.drift.app.LocalNavController
import dev.patrickgold.jetpref.datastore.ui.PreferenceLayout
import dev.patrickgold.jetpref.datastore.ui.PreferenceUiContent
import io.github.seshuthota.drift.lib.android.AndroidVersion
import io.github.seshuthota.drift.lib.compose.driftVerticalScroll

@Composable
fun DriftScreenNg(
    title: String,
    previewFieldVisible: Boolean,
    navigationIconVisible: Boolean = true,
    navigationIcon: ImageVector = Icons.AutoMirrored.Filled.ArrowBack,
    navigationIconOnClick: (() -> Unit)? = null,
    actions: DriftScreenActions = {},
    bottomBar: DriftScreenBottomBar = {},
    fab: DriftScreenFab = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val navController = LocalNavController.current
    val previewFieldController = LocalPreviewFieldController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    SideEffect {
        previewFieldController.isVisible = previewFieldVisible
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            DriftAppBar(
                title,
                (@Composable {
                    FilledTonalIconButton(
                        onClick = navigationIconOnClick ?: { navController.popBackStack(); Unit },
                        modifier = Modifier.padding(start = 8.dp, end = 4.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                        content = { Icon(navigationIcon, null) },
                    )
                }).takeIf { navigationIconVisible },
                actions,
                scrollBehavior,
            ) },
        bottomBar = bottomBar,
        floatingActionButton = fab,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        content = content,
    )
}

@Composable
fun DriftAppBar(
    title: String,
    navigationIcon: (@Composable () -> Unit)?,
    actions: @Composable RowScope.() -> Unit = { },
    scrollBehavior: TopAppBarScrollBehavior,
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    ),
) {
    MediumFlexibleTopAppBar(
        navigationIcon = navigationIcon ?: {},
        title = {
            Text(
                text = title,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
            )
        },
        actions = { Row(Modifier.padding(end = 8.dp), content = actions) },
        colors = colors,
        scrollBehavior = scrollBehavior,
    )
}

@Composable
fun DriftScreen(builder: @Composable DriftScreenScope.() -> Unit) {
    val scope = remember { DriftScreenScopeImpl() }
    builder(scope)
    scope.Render()
}

typealias DriftScreenActions = @Composable RowScope.() -> Unit
typealias DriftScreenBottomBar = @Composable () -> Unit
typealias DriftScreenContent = PreferenceUiContent<DriftPreferenceModel>
typealias DriftScreenFab = @Composable () -> Unit
typealias DriftScreenNavigationIcon = @Composable () -> Unit

interface DriftScreenScope {
    var title: String

    var navigationIconVisible: Boolean

    var previewFieldVisible: Boolean

    var scrollable: Boolean

    var iconSpaceReserved: Boolean

    fun actions(actions: DriftScreenActions)

    fun bottomBar(bottomBar: DriftScreenBottomBar)

    fun content(content: DriftScreenContent)

    fun floatingActionButton(fab: DriftScreenFab)

    fun navigationIcon(navigationIcon: DriftScreenNavigationIcon)
}

private class DriftScreenScopeImpl : DriftScreenScope {
    override var title: String by mutableStateOf("")
    override var navigationIconVisible: Boolean by mutableStateOf(true)
    override var previewFieldVisible: Boolean by mutableStateOf(false)
    override var scrollable: Boolean by mutableStateOf(true)
    override var iconSpaceReserved: Boolean by mutableStateOf(true)

    private var actions: DriftScreenActions = @Composable { }
    private var bottomBar: DriftScreenBottomBar = @Composable { }
    private var content: DriftScreenContent = @Composable { }
    private var fab: DriftScreenFab = @Composable { }
    private var navigationIcon: DriftScreenNavigationIcon = {
        val navController = LocalNavController.current
        FilledTonalIconButton(
            onClick = { navController.popBackStack() },
            modifier = Modifier.padding(start = 8.dp, end = 4.dp),
            content = { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) },
        )
    }

    override fun actions(actions: DriftScreenActions) {
        this.actions = actions
    }

    override fun bottomBar(bottomBar: DriftScreenBottomBar) {
        this.bottomBar = bottomBar
    }

    override fun content(content: DriftScreenContent) {
        this.content = content
    }

    override fun floatingActionButton(fab: DriftScreenFab) {
        this.fab = fab
    }

    override fun navigationIcon(navigationIcon: DriftScreenNavigationIcon) {
        this.navigationIcon = navigationIcon
    }

    @Composable
    fun Render() {
        val context = LocalContext.current
        val previewFieldController = LocalPreviewFieldController.current
        val colorScheme = MaterialTheme.colorScheme

        SideEffect {
            val window = (context as Activity).window
            previewFieldController?.isVisible = previewFieldVisible
            window.statusBarColor = Color.Transparent.toArgb()
            if (AndroidVersion.ATLEAST_API29_Q) {
                window.navigationBarColor = Color.Transparent.toArgb()
                window.isNavigationBarContrastEnforced = true
            } else {
                window.navigationBarColor = colorScheme.scrim.toArgb()
            }
        }

        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = { DriftAppBar(title, navigationIcon.takeIf { navigationIconVisible }, actions, scrollBehavior) },
            bottomBar = bottomBar,
            floatingActionButton = fab,
        ) { innerPadding ->
            val scrollModifier = if (scrollable) {
                Modifier.driftVerticalScroll()
            } else {
                Modifier
            }
            PreferenceLayout(
                DriftPreferenceStore,
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxWidth()
                    .then(scrollModifier),
                iconSpaceReserved = iconSpaceReserved,
                content = content,
            )
        }
    }
}
