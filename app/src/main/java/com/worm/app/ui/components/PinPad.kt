package com.worm.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

enum class PinMode { Unlock, Create, Verify }

private const val MIN_PIN = 4
private const val MAX_PIN = 8

/**
 * Number-pad dialog for the Burrow PIN.
 * - [PinMode.Unlock]/[PinMode.Verify]: [check] decides; [onSuccess] gets the PIN.
 * - [PinMode.Create]: asks twice, then [onSuccess] gets the new PIN.
 */
@Composable
fun PinDialog(
    mode: PinMode,
    onDismiss: () -> Unit,
    onSuccess: (String) -> Unit,
    check: suspend (String) -> Boolean = { true },
    title: String? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    var entered by remember { mutableStateOf("") }
    var firstEntry by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val shake = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    val heading = when {
        mode == PinMode.Create && firstEntry != null -> "Confirm your PIN"
        title != null -> title
        mode == PinMode.Create -> "Choose a PIN"
        mode == PinMode.Verify -> "Enter your current PIN"
        else -> "Open the Burrow"
    }
    val hint = error ?: when {
        mode == PinMode.Create && firstEntry == null -> "$MIN_PIN–$MAX_PIN digits. There's no way to recover it, so pick one you'll remember."
        mode == PinMode.Create -> "Type it once more"
        else -> " "
    }

    fun fail(msg: String) {
        error = msg
        entered = ""
        haptics.performHapticFeedback(HapticFeedbackType.Reject)
        scope.launch {
            for (x in listOf(24f, -20f, 14f, -8f, 4f, 0f)) shake.animateTo(x, tween(45))
        }
    }

    fun submit() {
        if (entered.length < MIN_PIN || checking) return
        val code = entered
        when (mode) {
            PinMode.Create -> {
                val first = firstEntry
                if (first == null) {
                    firstEntry = code; entered = ""; error = null
                } else if (first == code) {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onSuccess(code)
                } else {
                    firstEntry = null
                    fail("Those didn't match. Start again.")
                }
            }
            else -> scope.launch {
                checking = true
                val ok = check(code)
                checking = false
                if (ok) {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onSuccess(code)
                } else fail("Wrong PIN")
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(36.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(20.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
            ) {
                Box(
                    Modifier
                        .size(56.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, MaterialShapes.Cookie7Sided.toShape()),
                    contentAlignment = Alignment.Center,
                ) {
                    if (checking) LoadingIndicator(Modifier.size(36.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
                    else Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.height(16.dp))
                AnimatedContent(heading, label = "heading") {
                    Text(it, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(260.dp),
                )
                Spacer(Modifier.height(20.dp))
                // Entered digits: hollow slots that pop into cookie shapes.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .height(28.dp)
                        .graphicsLayer { translationX = shake.value },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val slots = maxOf(MIN_PIN, entered.length)
                    repeat(slots) { i ->
                        AnimatedContent(
                            targetState = i < entered.length,
                            transitionSpec = { scaleIn(spring(0.4f, 600f)) togetherWith scaleOut() + fadeOut() },
                            label = "dot",
                        ) { filled ->
                            if (filled) {
                                Box(
                                    Modifier
                                        .size(22.dp)
                                        .background(MaterialTheme.colorScheme.primary, MaterialShapes.Cookie6Sided.toShape()),
                                )
                            } else {
                                Box(
                                    Modifier
                                        .size(14.dp)
                                        .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "✓"))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    rows.forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            row.forEach { key ->
                                when (key) {
                                    "⌫" -> PadKey(icon = Icons.AutoMirrored.Rounded.Backspace, tonal = true) {
                                        entered = entered.dropLast(1); error = null
                                    }
                                    "✓" -> PadKey(icon = Icons.Rounded.Check, accent = true, enabled = entered.length >= MIN_PIN) { submit() }
                                    else -> PadKey(label = key) {
                                        if (entered.length < MAX_PIN) {
                                            entered += key; error = null
                                            haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    footer?.invoke()
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun PadKey(
    label: String? = null,
    icon: ImageVector? = null,
    tonal: Boolean = false,
    accent: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateFloatAsState(if (pressed) 28f else 50f, spring(0.45f, 700f), label = "c")
    val container = when {
        accent && enabled -> MaterialTheme.colorScheme.primary
        accent -> MaterialTheme.colorScheme.surfaceContainerHighest
        tonal -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = when {
        accent && enabled -> MaterialTheme.colorScheme.onPrimary
        accent -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        tonal -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(corner.toInt()),
        color = container,
        contentColor = content,
        interactionSource = interaction,
        modifier = Modifier.size(width = 76.dp, height = 60.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (label != null) Text(label, style = MaterialTheme.typography.headlineSmall)
            if (icon != null) Icon(icon, null)
        }
    }
}

