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

package io.github.seshuthota.drift.app.settings.about

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.seshuthota.drift.BuildConfig
import io.github.seshuthota.drift.R
import io.github.seshuthota.drift.app.LocalNavController
import io.github.seshuthota.drift.app.Routes
import io.github.seshuthota.drift.app.components.ScrollableScreenColumn
import io.github.seshuthota.drift.app.components.SegmentedListColumn
import io.github.seshuthota.drift.clipboardManager
import io.github.seshuthota.drift.app.components.DriftScreenNg
import dev.patrickgold.jetpref.datastore.ui.Preference
import io.github.seshuthota.drift.lib.android.stringRes
import io.github.seshuthota.drift.lib.compose.DriftCanvasIcon
import io.github.seshuthota.drift.lib.compose.stringRes

@Composable
fun AboutScreen() {
    val navController = LocalNavController.current
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val clipboardManager by context.clipboardManager()

    val appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    DriftScreenNg(
        title = stringRes(R.string.about__title),
        previewFieldVisible = false,
    ) { contentPadding ->
        ScrollableScreenColumn(contentPadding) {
            Column(
                verticalArrangement = Arrangement.Top,
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, bottom = 32.dp)
            ) {
                DriftCanvasIcon(
                    modifier = Modifier.requiredSize(64.dp),
                    iconId = R.mipmap.drift_app_icon,
                    contentDescription = "Drift app icon",
                )
                Text(
                    text = stringRes(R.string.drift_app_name),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            SegmentedListColumn {
                val count = 6
                Preference(
                    onClick = {
                        try {
                            clipboardManager.addNewPlaintext(appVersion)
                            Toast.makeText(context, R.string.about__version_copied__title, Toast.LENGTH_SHORT).show()
                        } catch (e: Throwable) {
                            Toast.makeText(
                                context,
                                context.stringRes(R.string.about__version_copied__error, "error_message" to e.message),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    shapes = ListItemDefaults.segmentedShapes(0, count),
                    icon = Icons.Outlined.Info,
                    title = stringRes(R.string.about__version__title),
                    summary = appVersion,
                )
                val changelogUrl = when {
                    BuildConfig.DEBUG -> stringRes(
                        R.string.drift__commit_by_hash_url,
                        "hash" to BuildConfig.BUILD_COMMIT_HASH,
                    )
                    else -> stringRes(
                        R.string.drift__changelog_url,
                        "version" to BuildConfig.VERSION_NAME,
                    )
                }
                Preference(
                    onClick = { uriHandler.openUri(changelogUrl) },
                    shapes = ListItemDefaults.segmentedShapes(1, count),
                    icon = Icons.Default.History,
                    title = stringRes(R.string.about__changelog__title),
                    summary = stringRes(R.string.about__changelog__summary),
                )
                val repoUrl = stringRes(R.string.drift__repo_url)
                Preference(
                    onClick = { uriHandler.openUri(repoUrl) },
                    shapes = ListItemDefaults.segmentedShapes(2, count),
                    icon = Icons.Default.Code,
                    title = stringRes(R.string.about__repository__title),
                    summary = stringRes(R.string.about__repository__summary),
                )
                val privacyPolicyUrl = stringRes(R.string.drift__privacy_policy_url)
                Preference(
                    onClick = { uriHandler.openUri(privacyPolicyUrl) },
                    shapes = ListItemDefaults.segmentedShapes(3, count),
                    icon = Icons.Outlined.Policy,
                    title = stringRes(R.string.about__privacy_policy__title),
                    summary = stringRes(R.string.about__privacy_policy__summary),
                )
                Preference(
                    onClick = { navController.navigate(Routes.Settings.ProjectLicense) },
                    shapes = ListItemDefaults.segmentedShapes(4, count),
                    icon = Icons.Outlined.Description,
                    title = stringRes(R.string.about__project_license__title),
                    summary = stringRes(R.string.about__project_license__summary, "license_name" to "Apache 2.0"),
                )
                Preference(
                    onClick = { navController.navigate(Routes.Settings.ThirdPartyLicenses) },
                    shapes = ListItemDefaults.segmentedShapes(5, count),
                    icon = Icons.Outlined.Description,
                    title = stringRes(id = R.string.about__third_party_licenses__title),
                    summary = stringRes(id = R.string.about__third_party_licenses__summary),
                )
            }
            Text(
                text = stringRes(R.string.about__training_data__title),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 16.dp, top = 24.dp, end = 16.dp),
            )
            Text(
                text = stringRes(R.string.about__training_data__body),
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
            )
        }
    }
}
