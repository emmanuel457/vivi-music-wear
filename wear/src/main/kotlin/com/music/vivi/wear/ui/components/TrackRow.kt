/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.wear.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import coil3.compose.AsyncImage
import com.music.vivi.wearsync.WearTrack

/**
 * One song in a list.
 *
 * Two lines maximum and a 40 dp thumbnail: anything denser is unreadable at
 * arm's length, and anything taller drops the visible row count below three on
 * a small watch.
 */
@Composable
fun TrackRow(
    track: WearTrack,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        // The playing row is distinguished by a lifted container rather than by
        // an accent fill, so a long list stays calm and the text contrast is
        // identical on every row.
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isPlaying) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Artwork(
                url = track.thumbnailUrl,
                isPlaying = isPlaying,
                modifier = Modifier.size(40.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (track.artist.isNotBlank()) {
                    Text(
                        text = track.artist,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalContentColor.current.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}

@Composable
fun Artwork(
    url: String?,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    cornerRadius: Int = 8,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius.dp))
            // Gives the placeholder a visible tile instead of an icon floating
            // on whatever happens to be behind it.
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNullOrBlank()) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                // Adapts whether this sits on a button or on the background.
                tint = LocalContentColor.current.copy(alpha = 0.7f),
                modifier = Modifier.padding(8.dp),
            )
        } else {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (isPlaying) {
            Icon(
                imageVector = Icons.Rounded.GraphicEq,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
