package com.bol.app.ui.screens.about

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.bol.app.data.oss.OssComponent
import com.bol.app.data.oss.OssLicenseType
import com.bol.app.data.oss.OssLicenses
import com.bol.app.ui.components.HeedCard
import com.bol.app.ui.components.HeedLayout
import com.bol.app.ui.components.TopBar
import com.bol.app.ui.components.PanelDivider
import com.bol.app.ui.components.PanelRow
import com.bol.app.ui.components.SectionLabel
import com.bol.app.ui.theme.BolFonts
import com.bol.app.ui.theme.HeedTheme

@Composable
fun OssLicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        TopBar(title = "Open source licenses", onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = HeedLayout.Gutter)) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Heed is built on the open source projects below. " +
                "Tap an item to open its homepage. License texts are at the bottom.",
            style = MaterialTheme.typography.bodyMedium,
            color = HeedTheme.colors.textSecondary,
        )

        // ---- AI models ----
        SectionLabel("AI Models")
        HeedCard {
            OssLicenses.models.forEachIndexed { index, comp ->
                if (index > 0) PanelDivider()
                ComponentRow(comp) { openUrl(context, comp.url) }
            }
        }

        // ---- Fonts ----
        SectionLabel("Fonts")
        HeedCard {
            OssLicenses.fonts.forEachIndexed { index, comp ->
                if (index > 0) PanelDivider()
                ComponentRow(comp) { openUrl(context, comp.url) }
            }
        }

        // ---- Bundled libraries ----
        SectionLabel("Libraries")
        HeedCard {
            OssLicenses.libraries.forEachIndexed { index, comp ->
                if (index > 0) PanelDivider()
                ComponentRow(comp) { openUrl(context, comp.url) }
            }
        }

        // ---- License texts ----
        SectionLabel("License texts")
        HeedCard {
            ExpandableLicenseRow(
                title = OssLicenseType.APACHE_2_0.displayName,
                body = OssLicenses.APACHE_2_0_TEXT,
            )
            PanelDivider()
            ExpandableLicenseRow(
                title = OssLicenseType.MIT.displayName,
                body = OssLicenses.MIT_TEXT,
            )
            PanelDivider()
            ExpandableLicenseRow(
                title = OssLicenseType.OFL.displayName,
                body = OssLicenses.OFL_TEXT,
            )
            PanelDivider()
            ExpandableLicenseRow(
                title = OssLicenseType.GEMMA.displayName,
                body = OssLicenses.GEMMA_NOTICE,
            )
            PanelDivider()
            ExpandableLicenseRow(
                title = "SQLite (${OssLicenseType.PUBLIC_DOMAIN.displayName.lowercase()})",
                body = OssLicenses.SQLITE_NOTICE,
            )
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = "This list covers Heed's direct dependencies. Each library may " +
                "include further open source notices in its own source repository, " +
                "linked above.",
            style = MaterialTheme.typography.bodySmall,
            color = HeedTheme.colors.textMuted,
        )
        Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun ComponentRow(comp: OssComponent, onClick: () -> Unit) {
    // No `right` slot here: a long model version string ("litert-community/...")
    // would have no width constraint and squeeze the label column down to a
    // single character per line. Keep everything in label/sub and let Text
    // wrap naturally.
    PanelRow(
        label = comp.name,
        sub = "${comp.role}\n${comp.author} · ${comp.license.displayName} · ${comp.version}",
        onClick = onClick,
    )
}

@Composable
private fun ExpandableLicenseRow(title: String, body: String) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        PanelRow(
            label = title,
            onClick = { expanded = !expanded },
            right = {
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp
                                  else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = HeedTheme.colors.textMuted,
                )
            },
        )
        AnimatedVisibility(visible = expanded) {
            Surface(
                color = HeedTheme.colors.surfaceSoft,
                shape = HeedLayout.ControlShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = BolFonts.Mono,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    ),
                    color = HeedTheme.colors.textSecondary,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
