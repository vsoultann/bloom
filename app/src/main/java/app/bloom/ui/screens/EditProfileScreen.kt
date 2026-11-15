package app.bloom.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.bloom.AppViewModel
import app.bloom.ui.components.*
import app.bloom.ui.theme.LocalLook
import app.bloom.ui.theme.hueColor
import coil3.compose.AsyncImage

/**
 * Edit everything on your Root profile: banner, picture, username, bio, custom
 * status and presence. The card at the top is a live preview; only changed
 * fields are sent.
 */
@Composable
fun EditProfileScreen(vm: AppViewModel, onBack: () -> Unit) {
    val me = vm.me ?: return
    val scheme = MaterialTheme.colorScheme
    var username by remember { mutableStateOf(me.name) }
    var bio by remember { mutableStateOf(me.bio) }
    var status by remember { mutableStateOf(me.statusText) }
    var presence by remember { mutableStateOf<String?>(null) }
    var avatar by remember { mutableStateOf<Uri?>(null) }
    var banner by remember { mutableStateOf<Uri?>(null) }
    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { u -> avatar = u } }
    val pickBanner = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { u -> banner = u } }
    val changed = username != me.name || bio != me.bio || status != me.statusText || presence != null || avatar != null || banner != null
    val image = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

    Column(Modifier.fillMaxSize().background(scheme.surface).verticalScroll(rememberScrollState()).systemBarsPadding().imePadding()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Edit profile", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Button(onClick = {
                vm.saveProfile(
                    username.takeIf { it != me.name }, bio.takeIf { it != me.bio }, status.takeIf { it != me.statusText },
                    presence, avatar, banner,
                ) { onBack() }
            }, enabled = changed && !vm.savingProfile) {
                if (vm.savingProfile) MorphingLoader(size = 20.dp, color = scheme.onPrimary) else Text("Save")
            }
        }

        // live preview card
        Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(LocalLook.current.cardRadius)).background(scheme.surfaceContainer)) {
            Column {
                Box(
                    Modifier.fillMaxWidth().height(130.dp).background(Brush.linearGradient(listOf(hueColor(me.hue), hueColor(me.hue + 50, tone = 0.42f))))
                        .clickable { pickBanner.launch(image) },
                ) {
                    val b = banner ?: me.bannerUrl
                    if (b != null) AsyncImage(b, "Banner", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Row(
                        Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.PhotoCamera, null, Modifier.size(16.dp), tint = Color.White)
                        Spacer(Modifier.width(4.dp))
                        Text("Change banner", color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                }
                Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp).offset(y = (-36).dp), verticalAlignment = Alignment.Bottom) {
                    Box(Modifier.size(88.dp).clip(CircleShape).border(4.dp, scheme.surfaceContainer, CircleShape).clickable { pickAvatar.launch(image) }) {
                        if (avatar != null) AsyncImage(avatar, "Picture", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Avatar(me.displayName, me.hue, 88.dp, userId = me.id)
                        Box(Modifier.align(Alignment.BottomEnd).size(28.dp).clip(CircleShape).background(scheme.primary), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.PhotoCamera, "Change picture", Modifier.size(16.dp), tint = scheme.onPrimary)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.padding(bottom = 4.dp)) {
                        Text(username.ifBlank { me.name }, style = MaterialTheme.typography.titleLarge)
                        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    }
                }
            }
        }

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(username, { username = it.filter { c -> !c.isWhitespace() } }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                status, { status = it.take(128) }, label = { Text("Custom status") },
                supportingText = { Text("${status.length}/128") }, singleLine = false, maxLines = 3, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                bio, { bio = it.take(500) }, label = { Text("About me") },
                supportingText = { Text("${bio.length}/500") }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth(),
            )
            Text("Presence", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("online" to "Online", "idle" to "Idle", "invisible" to "Invisible").forEach { (k, label) ->
                    FilterChip(presence == k, { presence = if (presence == k) null else k }, { Text(label) })
                }
            }
            Text("Tap the banner or picture above to change them.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}
