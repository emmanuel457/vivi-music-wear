/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.wear.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

/** A top-level navigation row: icon, title, optional count. */
@Composable
fun SectionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryLabel: String? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        // Dark container, light content. The default filled Button puts dark
        // text on the light `primary` colour, which is hard to read on a watch
        // outdoors and left no readable colour for a secondary line.
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (secondaryLabel != null) {
                    Text(
                        text = secondaryLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        // Derived from the button's own content colour, not a
                        // surface token — the container this sits on is set by
                        // the button, so a global token can be invisible here.
                        color = LocalContentColor.current.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}

/** Centred placeholder for empty lists and failed loads. */
@Composable
fun StatusMessage(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 24.dp),
    )
}

@Composable
fun LoadingRow(modifier: Modifier = Modifier) {
    Row(
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp))
    }
}
