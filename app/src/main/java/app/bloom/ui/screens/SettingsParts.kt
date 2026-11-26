package app.bloom.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import app.bloom.ui.theme.LocalLook
import app.bloom.ui.theme.Motion

/** Collapsible settings group: header with an icon and a rotating chevron; body springs open. */
@Composable
fun ExpandableSection(
    title: String,
    subtitle: String,
    icon: ImageVector,
    initiallyOpen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var open by rememberSaveableBool(title, initiallyOpen)
    val scheme = MaterialTheme.colorScheme
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val spin by animateFloatAsState(if (open) 180f else 0f, Motion.spatial(), label = "chev")
    // expressive: the icon badge morphs rounded-square → circle as the section opens
    val morph by animateFloatAsState(if (open) 1f else 0f, Motion.spring(0.6f, 420f), label = "badge")
    val bg by animateColorAsState(if (open) scheme.surfaceContainerHigh else scheme.surfaceContainer, Motion.effects(), label = "secBg")
    Column(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth()
            .clip(RoundedCornerShape(LocalLook.current.cardRadius))
            .background(bg)
            .animateContentSize(Motion.spring(0.82f, 380f)),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable {
                haptic.performHapticFeedback(if (open) androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOff else androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOn)
                open = !open
            }.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(percent = (28 + 22 * morph).toInt().coerceIn(0, 50)))
                    .background(if (open) scheme.primary else scheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, Modifier.size(22.dp), tint = if (open) scheme.onPrimary else scheme.onSecondaryContainer) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2)
            }
            Icon(Icons.Rounded.ExpandMore, if (open) "Collapse" else "Expand", Modifier.graphicsLayer { rotationZ = spin })
        }
        if (open) Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/** Material 3 Expressive switch: check / cross in the thumb, haptic tick on toggle. */
@Composable
fun BloomSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Switch(
        checked,
        onCheckedChange?.let { cb -> { v: Boolean ->
            haptic.performHapticFeedback(if (v) androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOn else androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOff)
            cb(v)
        } },
        modifier, enabled = enabled,
        thumbContent = {
            Icon(if (checked) Icons.Rounded.Check else Icons.Rounded.Close, null, Modifier.size(SwitchDefaults.IconSize))
        },
    )
}

@Composable
private fun rememberSaveableBool(key: String, initial: Boolean): MutableState<Boolean> =
    androidx.compose.runtime.saveable.rememberSaveable(key) { mutableStateOf(initial) }

@Composable
fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, format: (Float) -> String, steps: Int = 0, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(format(value), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value, onChange, valueRange = range, steps = steps)
    }
}

/** HSV + alpha colour picker with hex input and live preview. */
@Composable
fun ColorPickerDialog(title: String, initial: Color, onDismiss: () -> Unit, onReset: () -> Unit, onPick: (Color) -> Unit) {
    val hsl = remember { FloatArray(3) }
    val start = remember(initial) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(initial.toArgb(), hsv)
        hsv
    }
    var h by remember { mutableFloatStateOf(start[0]) }
    var s by remember { mutableFloatStateOf(start[1]) }
    var v by remember { mutableFloatStateOf(start[2]) }
    var a by remember { mutableFloatStateOf(initial.alpha) }
    val color = Color(android.graphics.Color.HSVToColor((a * 255).toInt(), floatArrayOf(h, s, v)))
    var hex by remember(color) { mutableStateOf(String.format("%08X", color.toArgb())) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // saturation/value square
                Box(
                    Modifier.fillMaxWidth().height(160.dp).clip(app.bloom.ui.theme.Radii.card)
                        .pointerInput(h) {
                            fun set(o: Offset) {
                                s = (o.x / size.width).coerceIn(0f, 1f)
                                v = 1f - (o.y / size.height).coerceIn(0f, 1f)
                            }
                            detectTapGestures { set(it) }
                        }
                        .pointerInput(h) {
                            detectDragGestures { ch, _ ->
                                s = (ch.position.x / size.width).coerceIn(0f, 1f)
                                v = 1f - (ch.position.y / size.height).coerceIn(0f, 1f)
                            }
                        },
                ) {
                    val hue = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, 1f, 1f)))
                    Canvas(Modifier.fillMaxSize()) {
                        drawRect(Brush.horizontalGradient(listOf(Color.White, hue)))
                        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                        drawCircle(Color.White, 10.dp.toPx(), Offset(s * size.width, (1 - v) * size.height), style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
                    }
                }
                GradientSlider("Hue", h / 360f, Brush.horizontalGradient((0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) })) { h = it * 360f }
                GradientSlider("Opacity", a, Brush.horizontalGradient(listOf(color.copy(alpha = 0f), color.copy(alpha = 1f)))) { a = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape).background(color))
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        hex,
                        { t ->
                            hex = t.uppercase().filter { it.isLetterOrDigit() }.take(8)
                            val parsed = hex.takeIf { it.length == 6 || it.length == 8 }?.let { runCatching { (if (it.length == 6) "FF$it" else it).toLong(16).toInt() }.getOrNull() }
                            if (parsed != null) {
                                val hsv = FloatArray(3)
                                android.graphics.Color.colorToHSV(parsed, hsv)
                                h = hsv[0]; s = hsv[1]; v = hsv[2]
                                a = (parsed ushr 24) / 255f
                            }
                        },
                        prefix = { Text("#") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.weight(1f),
                    )
                }
                val contrast = ColorUtils.calculateContrast(Color.White.toArgb(), color.copy(alpha = 1f).toArgb())
                Text(
                    "Contrast with white text: ${"%.1f".format(contrast)}:1",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onPick(color) }) { Text("Apply") } },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) { Text("Default") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun GradientSlider(label: String, value: Float, track: Brush, onChange: (Float) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box(
            Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(50)).background(track)
                .pointerInput(Unit) { detectTapGestures { onChange((it.x / size.width).coerceIn(0f, 1f)) } }
                .pointerInput(Unit) { detectDragGestures { ch, _ -> onChange((ch.position.x / size.width).coerceIn(0f, 1f)) } },
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                Box(
                    Modifier.offset(x = (maxWidth - 24.dp) * value.coerceIn(0f, 1f) + 2.dp).align(Alignment.CenterStart)
                        .size(24.dp).clip(CircleShape).border(3.dp, Color.White, CircleShape),
                )
            }
        }
    }
}
