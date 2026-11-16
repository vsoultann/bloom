package app.bloom.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.bloom.AppViewModel
import app.bloom.core.Message
import app.bloom.ui.components.*
import app.bloom.ui.theme.LocalLook
import app.bloom.ui.theme.IconMode
import app.bloom.ui.theme.hueColor
import coil3.compose.AsyncImage

/** Chat window background: soft theme gradient or your own image (dim + blur). */
@Composable
fun ChatBackground(vm: AppViewModel, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    when (vm.chatBg) {
        "gradient" -> Box(
            modifier.fillMaxSize().background(
                Brush.linearGradient(
                    listOf(scheme.primaryContainer.copy(alpha = 0.55f), scheme.surfaceContainer, scheme.tertiaryContainer.copy(alpha = 0.45f)),
                    start = Offset.Zero, end = Offset(1200f, 2600f),
                ),
            ),
        )
        "pattern" -> Canvas(modifier.fillMaxSize().background(scheme.surfaceContainerLow)) {
            // soft scattered blooms in the theme colours
            val step = 120.dp.toPx()
            var row = 0
            var y = step / 2
            while (y < size.height + step) {
                var x = if (row % 2 == 0) step / 2 else step
                while (x < size.width + step) {
                    drawCircle(scheme.primary.copy(alpha = 0.07f), radius = step * 0.22f, center = Offset(x, y))
                    drawCircle(scheme.tertiary.copy(alpha = 0.06f), radius = step * 0.1f, center = Offset(x + step * 0.3f, y - step * 0.25f))
                    x += step
                }
                y += step * 0.85f; row++
            }
        }
        "image" -> vm.chatBgImage?.let { img ->
            Box(modifier.fillMaxSize()) {
                Image(img, null, Modifier.fillMaxSize().blur(vm.chatBgBlur.dp), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(scheme.surface.copy(alpha = vm.chatBgDim)))
            }
        }
        else -> {}
    }
}

/** Skeleton rows for media still uploading, with a progress bar. */
@Composable
fun OutgoingMediaRow(vm: AppViewModel, o: AppViewModel.Outgoing) {
    val scheme = MaterialTheme.colorScheme
    val me = vm.me ?: return
    val shimmer by rememberInfiniteTransition(label = "sk").animateFloat(0.25f, 0.6f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    val progress by animateFloatAsState(o.progress, app.bloom.ui.theme.Motion.tween(250), label = "p")
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Avatar(me.displayName, me.hue, 40.dp, userId = me.id)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(me.displayName, style = MaterialTheme.typography.titleSmall, color = hueColor(me.hue))
            if (o.text.isNotBlank()) Text(o.text, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface.copy(alpha = 0.6f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                o.items.take(4).forEach { p ->
                    // the photo's / video's own shape (falls back to a square)
                    val ratio = if (p.width > 0 && p.height > 0) (p.width.toFloat() / p.height).coerceIn(0.4f, 3f) else 1f
                    val box = if (o.items.size == 1) Modifier.widthIn(max = 260.dp).heightIn(max = 320.dp).aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f)
                        else Modifier.height(110.dp).aspectRatio(ratio)
                    Box(box.clip(app.bloom.ui.theme.Radii.tile).background(scheme.surfaceContainerHighest)) {
                        if (p.isImage || p.isVideo) AsyncImage(p.uri, null, Modifier.fillMaxSize().graphicsLayer { alpha = 0.45f }, contentScale = ContentScale.Crop)
                        Box(Modifier.fillMaxSize().background(scheme.onSurface.copy(alpha = shimmer * 0.25f)))
                    }
                }
            }
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.padding(top = 8.dp).widthIn(max = 220.dp).fillMaxWidth().clip(RoundedCornerShape(50)))
            Text("Uploading… ${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/**
 * Who's typing. Style "avatars": each typer's picture inside a slowly morphing,
 * spinning Material shape, overlapping when several people type. "shapes": just
 * the morphing shape. "dots": three bouncing dots. "text": text only.
 */
@Composable
fun TypingIndicator(vm: AppViewModel, typers: List<Long>) {
    val scheme = MaterialTheme.colorScheme
    val names = typers.mapNotNull { vm.users[it]?.displayName }
    val label = when (names.size) {
        0 -> ""
        1 -> "${names[0]} is typing"
        2 -> "${names[0]} and ${names[1]} are typing"
        3 -> "${names[0]}, ${names[1]} and ${names[2]} are typing"
        else -> "${names.size} people are typing"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (vm.typingStyle) {
            "avatars" -> Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
                typers.take(4).forEachIndexed { i, id ->
                    val u = vm.users[id]
                    val living = rememberLivingShape(seed = (id % 997).toInt() + i, selected = false)
                    Box(Modifier.size(22.dp).graphicsLayer { shape = living.shape(); clip = true }.background(scheme.primaryContainer)) {
                        if (u != null) Avatar(u.displayName, u.hue, 22.dp, userId = u.id, shape = androidx.compose.ui.graphics.RectangleShape)
                    }
                }
            }
            "shapes" -> MorphingLoader(size = 16.dp)
            "dots" -> TypingDots()
            else -> {}
        }
        if (vm.typingStyle != "text") Spacer(Modifier.width(8.dp))
        Text("$label…", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TypingDots() {
    val c = MaterialTheme.colorScheme.primary
    val t = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val y by t.animateFloat(0f, 1f, infiniteRepeatable(keyframes { durationMillis = 900; 0f at (i * 150); 1f at (i * 150 + 220); 0f at (i * 150 + 450) }), label = "d$i")
            Box(Modifier.size(6.dp).graphicsLayer { translationY = -y * 6f }.clip(CircleShape).background(c))
        }
    }
}

/**
 * Reply preview above a message: a curved connector from the avatar column, the
 * replied-to author's small avatar and name (in their colour), and a one-line,
 * muted snippet. Tap to jump to the original.
 */
@Composable
fun ReplyPreview(vm: AppViewModel, m: Message, all: List<Message>, compact: Boolean, onJump: (Long) -> Unit) {
    val rid = m.replyTo ?: return
    ReplyLine(vm, rid, m.replyAuthor, m.replySnippet, all, compact, onJump)
    // replying to several messages at once: one line each
    m.moreReplies.forEach { r -> if (r.id != rid) ReplyLine(vm, r.id, r.author, r.snippet, all, compact, onJump) }
}

@Composable
private fun ReplyLine(vm: AppViewModel, rid: Long, replyAuthor: Long?, replySnippet: String?, all: List<Message>, compact: Boolean, onJump: (Long) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val target = all.firstOrNull { it.id == rid }
    val author = target?.let { vm.users[it.authorId] } ?: replyAuthor?.let { vm.users[it] }
    val snippet = plainText(target?.content ?: replySnippet ?: "").ifBlank { if (target?.attachments?.isNotEmpty() == true) "Attachment" else "Original message unavailable" }
    val avatarCol = if (compact) 32.dp else 40.dp
    val line = scheme.outlineVariant
    Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
        // curved connector: starts above the avatar's centre, bends right into the preview
        Canvas(Modifier.width(avatarCol + 12.dp).fillMaxHeight()) {
            val x = avatarCol.toPx() / 2
            val p = Path().apply {
                moveTo(x, size.height)
                lineTo(x, size.height / 2 + 6.dp.toPx())
                quadraticTo(x, size.height / 2, x + 6.dp.toPx(), size.height / 2)
                lineTo(size.width - 2.dp.toPx(), size.height / 2)
            }
            drawPath(p, line, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(LocalLook.current.rowRadius)).clickable { onJump(rid) }.padding(horizontal = 4.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (author != null) Avatar(author.displayName, author.hue, 16.dp, userId = author.id)
            Spacer(Modifier.width(6.dp))
            Text(author?.displayName ?: "Unknown", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = author?.let { hueColor(it.hue) } ?: scheme.onSurfaceVariant, maxLines = 1)
            Spacer(Modifier.width(6.dp))
            Text(snippet, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
