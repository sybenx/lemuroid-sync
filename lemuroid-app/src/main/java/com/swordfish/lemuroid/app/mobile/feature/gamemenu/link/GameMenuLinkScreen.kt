package com.swordfish.lemuroid.app.mobile.feature.gamemenu.link

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
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
            Divider(modifier = Modifier.fillMaxWidth())
            FriendsList(state, onFriendClicked = viewModel::invite, onForget = viewModel::forget)
            AddFriendCard(
                code = state.code,
                onShowCode = viewModel::showMyCode,
                onEnterCode = viewModel::enterCode,
                onDismiss = viewModel::dismissCode,
            )
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
            val outgoing = state.outgoing?.takeIf { it.deviceId == peer.deviceId }
            val status = outgoingStatus(outgoing) ?: peer.gameTitle
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
private fun outgoingStatus(outgoing: GameMenuLinkViewModel.Outgoing?): String? {
    return when (outgoing) {
        is GameMenuLinkViewModel.Outgoing.Waiting -> stringResource(R.string.game_link_waiting)
        is GameMenuLinkViewModel.Outgoing.Declined -> stringResource(R.string.game_link_declined)
        is GameMenuLinkViewModel.Outgoing.Busy -> stringResource(R.string.game_link_busy)
        is GameMenuLinkViewModel.Outgoing.Failed -> stringResource(R.string.game_link_failed)
        null -> null
    }
}

/** Friends who aren't on this Wi-Fi: they can be invited wherever they are, while they have Link Cable open. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FriendsList(
    state: GameMenuLinkViewModel.State,
    onFriendClicked: (GameMenuLinkViewModel.Friend) -> Unit,
    onForget: (GameMenuLinkViewModel.Friend) -> Unit,
) {
    var forgetting by remember { mutableStateOf<GameMenuLinkViewModel.Friend?>(null) }
    val friends = state.friends.filterNot { it.isNearby }

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
            SectionLabel(stringResource(R.string.game_link_friends))
        }

        val hint =
            when {
                state.friends.isEmpty() -> stringResource(R.string.game_link_friends_empty)
                !state.relayConnected -> stringResource(R.string.game_link_friends_offline)
                else -> null
            }
        hint?.let {
            Text(
                text = it,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        friends.forEach { friend ->
            val outgoing = state.outgoing?.takeIf { it.deviceId == friend.friend.deviceId }
            val status =
                outgoingStatus(outgoing)
                    ?: friend.online?.let { online ->
                        if (online.gameTitle.isNotBlank()) {
                            stringResource(R.string.game_link_friend_playing, online.gameTitle)
                        } else {
                            stringResource(R.string.game_link_friend_online)
                        }
                    }
                    ?: stringResource(R.string.game_link_friend_offline)
            ListItem(
                modifier =
                    Modifier.combinedClickable(
                        enabled = true,
                        onClick = { onFriendClicked(friend) },
                        onLongClick = { forgetting = friend },
                    ),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { LinkAvatar(friend.friend.identity, size = 40.dp) },
                headlineContent = {
                    Text(
                        text = friend.friend.identity.displayName,
                        color =
                            if (friend.online != null) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                },
                supportingContent = { Text(status) },
                trailingContent = {
                    if (outgoing is GameMenuLinkViewModel.Outgoing.Waiting) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                },
            )
        }
    }

    forgetting?.let { friend ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text(stringResource(R.string.game_link_forget_title, friend.friend.identity.displayName)) },
            text = { Text(stringResource(R.string.game_link_forget_message)) },
            confirmButton = {
                TextButton(onClick = {
                    onForget(friend)
                    forgetting = null
                }) { Text(stringResource(R.string.game_link_forget)) }
            },
            dismissButton = {
                TextButton(onClick = { forgetting = null }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

/** Adds a friend who has never been on the same Wi-Fi: one shows a code, the other enters it. */
@Composable
private fun AddFriendCard(
    code: GameMenuLinkViewModel.Code?,
    onShowCode: () -> Unit,
    onEnterCode: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var entering by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.game_link_add_friend_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onShowCode) { Text(stringResource(R.string.game_link_show_code)) }
            OutlinedButton(onClick = {
                typed = ""
                entering = true
            }) { Text(stringResource(R.string.game_link_enter_code)) }
        }
    }

    if (code is GameMenuLinkViewModel.Code.Mine && !entering) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.game_link_your_code)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = code.code.chunked(3).joinToString(" "),
                        style = MaterialTheme.typography.displaySmall,
                    )
                    Spacer(modifier = Modifier.size(12.dp))
                    Text(stringResource(R.string.game_link_your_code_hint), textAlign = TextAlign.Center)
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.game_link_done)) } },
        )
    }

    if (entering) {
        val valid = typed.length == CODE_LENGTH
        AlertDialog(
            onDismissRequest = {
                entering = false
                onDismiss()
            },
            title = { Text(stringResource(R.string.game_link_enter_code)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { value -> typed = value.filter { it.isDigit() }.take(CODE_LENGTH) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = MaterialTheme.typography.headlineSmall,
                    )
                    when (code) {
                        GameMenuLinkViewModel.Code.Invalid ->
                            Text(
                                text = stringResource(R.string.game_link_code_invalid),
                                modifier = Modifier.padding(top = 8.dp),
                                color = MaterialTheme.colorScheme.error,
                            )
                        GameMenuLinkViewModel.Code.Loading ->
                            CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp).size(20.dp), strokeWidth = 2.dp)
                        else -> Unit
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = valid, onClick = { onEnterCode(typed) }) {
                    Text(stringResource(R.string.game_link_add_friend))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    entering = false
                    onDismiss()
                }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
        // The friend arrived: the code is cleared, so close.
        LaunchedEffect(code) {
            if (code == null && typed.length == CODE_LENGTH) entering = false
        }
    }
}

private const val CODE_LENGTH = 6

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
