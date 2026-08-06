/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.wear.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.music.vivi.wear.R
import com.music.vivi.wear.playback.ActiveRoute
import com.music.vivi.wear.playback.UiPlaybackState

/**
 * The "resume what's playing" affordance at the top of Home.
 *
 * Shows which device the audio is on — without that, a paused watch and a
 * paused phone look identical, and tapping play sends the sound somewhere the
 * user did not expect.
 */
@Composable
fun NowPlayingCard(
    state: UiPlaybackState,
    onClick: () -> Unit,
    onTogglePlayPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = state.track ?: return

    // Two separate targets rather than a nested one: the card opens Now Playing,
    // the trailing button toggles transport. A tap zone inside a button is
    // ambiguous to hit and has no distinct accessibility node.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Button(
            onClick = onClick,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.filledTonalButtonColors(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Artwork(url = track.thumbnailUrl, modifier = Modifier.size(36.dp))

                Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = if (state.route == ActiveRoute.PHONE) {
                            Icons.Rounded.Smartphone
                        } else {
                            Icons.Rounded.Watch
                        },
                        contentDescription = stringResource(R.string.cd_output),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = stringResource(
                            if (state.route == ActiveRoute.PHONE) {
                                R.string.playing_on_phone
                            } else {
                                R.string.playing_on_watch
                            }
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    }
                }
            }
        }

        FilledIconButton(
            onClick = onTogglePlayPause,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = stringResource(
                    if (state.isPlaying) R.string.cd_pause else R.string.cd_play
                ),
            )
        }
    }
}
