package com.swordfish.lemuroid.app.mobile.feature.gamemenu.link

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.link.GameLink
import com.swordfish.lemuroid.app.shared.link.LinkColor
import com.swordfish.lemuroid.app.shared.link.LinkCreature
import com.swordfish.lemuroid.app.shared.link.LinkIdentity
import com.swordfish.lemuroid.app.shared.link.LinkPeer

@Composable
fun GameMenuLinkScreen(
    viewModel: GameMenuLinkViewModel,
    onLinkChanged: (GameLink.Connection) -> Unit,
) {
    LaunchedEffect(viewModel) {
        viewModel.results.collect { onLinkChanged(it) }
    }

    val state by viewModel.state.collectAsState()
    val linkedPeer = viewModel.connection.peer

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        if (linkedPeer != null) {
            LinkedCard(linkedPeer, onUnplug = viewModel::unplug)
        } else {
            NameCard(
                identity = state.identity,
                onShuffle = viewModel::shuffleIdentity,
                onIdentityChanged = viewModel::setIdentity,
            )
            Divider(modifier = Modifier.fillMaxWidth())
            NearbyList(state, onPeerClicked = viewModel::invite)
        }
    }

    state.incoming?.let { invite ->
        AlertDialog(
            onDismissRequest = { viewModel.answerInvite(false) },
            icon = { LinkAvatar(invite.from, size = 56.dp) },
            title = {
                Text(
                    text = stringResource(R.string.game_link_invite_title, invite.from.displayName),
                    textAlign = TextAlign.Center,
                )
            },
            text = {
                if (invite.gameTitle.isNotBlank()) {
                    Text(stringResource(R.string.game_link_invite_game, invite.gameTitle))
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.answerInvite(true) }) {
                    Text(stringResource(R.string.game_link_invite_accept))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.answerInvite(false) }) {
                    Text(stringResource(R.string.game_link_invite_decline))
                }
            },
        )
    }
}

@Composable
private fun LinkedCard(
    peer: LinkIdentity,
    onUnplug: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LinkAvatar(peer, size = 72.dp)
        Text(
            text = stringResource(R.string.game_link_linked, peer.displayName),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.game_link_linked_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onUnplug) {
            Text(stringResource(R.string.game_link_unplug))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NameCard(
    identity: LinkIdentity,
    onShuffle: () -> Unit,
    onIdentityChanged: (LinkIdentity) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinkAvatar(identity, size = 56.dp)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.game_link_you_are),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = identity.displayName, style = MaterialTheme.typography.titleLarge)
            }
            IconButton(onClick = onShuffle) {
                Icon(Icons.Filled.Shuffle, stringResource(R.string.game_link_shuffle))
            }
            IconButton(onClick = { editing = !editing }) {
                Icon(Icons.Filled.Edit, stringResource(R.string.game_link_change))
            }
        }

        if (editing) {
            SectionLabel(stringResource(R.string.game_link_color))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LinkColor.entries.forEach { color ->
                    val selected = color == identity.color
                    Box(
                        modifier =
                            Modifier
                                .size(36.dp)
                                .border(
                                    width = if (selected) 3.dp else 0.dp,
                                    color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                    shape = CircleShape,
                                )
                                .padding(4.dp)
                                .background(Color(color.argb), CircleShape)
                                .clickable { onIdentityChanged(identity.copy(color = color)) },
                    )
                }
            }
            SectionLabel(stringResource(R.string.game_link_creature))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                LinkCreature.entries.forEach { creature ->
                    val selected = creature == identity.creature
                    Box(
                        modifier =
                            Modifier
                                .size(44.dp)
                                .background(
                                    if (selected) Color(identity.color.argb) else Color.Transparent,
                                    RoundedCornerShape(12.dp),
                                )
                                .clickable { onIdentityChanged(identity.copy(creature = creature)) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = creature.emoji, fontSize = 24.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun NearbyList(
    state: GameMenuLinkViewModel.State,
    onPeerClicked: (LinkPeer) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
            SectionLabel(stringResource(R.string.game_link_nearby))
        }

        if (state.peers.isEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.game_link_looking),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        state.peers.forEach { peer ->
            val outgoing = state.outgoing?.takeIf { it.peer.deviceId == peer.deviceId }
            val status =
                when (outgoing) {
                    is GameMenuLinkViewModel.Outgoing.Waiting -> stringResource(R.string.game_link_waiting)
                    is GameMenuLinkViewModel.Outgoing.Declined -> stringResource(R.string.game_link_declined)
                    is GameMenuLinkViewModel.Outgoing.Busy -> stringResource(R.string.game_link_busy)
                    is GameMenuLinkViewModel.Outgoing.Failed -> stringResource(R.string.game_link_failed)
                    null -> peer.gameTitle
                }
            ListItem(
                modifier = Modifier.clickable { onPeerClicked(peer) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { LinkAvatar(peer.identity, size = 40.dp) },
                headlineContent = { Text(peer.identity.displayName) },
                supportingContent = { if (status.isNotBlank()) Text(status) },
                trailingContent = {
                    if (outgoing is GameMenuLinkViewModel.Outgoing.Waiting) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                },
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
fun LinkAvatar(
    identity: LinkIdentity,
    size: Dp,
) {
    Box(
        modifier = Modifier.size(size).background(Color(identity.color.argb), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = identity.creature.emoji, fontSize = (size.value * 0.5f).sp)
    }
}
