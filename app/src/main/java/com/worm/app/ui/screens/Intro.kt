package com.worm.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.worm.app.R
import com.worm.app.ui.components.PixelField
import com.worm.app.ui.theme.LocalWormColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@Composable
private fun IntroBackdrop(content: @Composable () -> Unit) {
    val wc = LocalWormColors.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(wc.glowA.copy(alpha = 0.55f), wc.backdrop), radius = 1500f)),
    ) {
        if (wc.particles) PixelField(Modifier.fillMaxSize(), count = 50, speed = 1.6f, colors = wc.dust)
        content()
    }
}

@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val scale = remember { Animatable(0.5f) }
    val tilt = remember { Animatable(-10f) }
    val textAlpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { scale.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 220f)) }
        launch { tilt.animateTo(0f, spring(dampingRatio = 0.28f, stiffness = 160f)) }
        launch { delay(250); textAlpha.animateTo(1f, tween(450)) }
        delay(1300)
        onFinished()
    }
    IntroBackdrop {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painterResource(R.drawable.worm_logo),
                contentDescription = "Worm",
                modifier = Modifier
                    .fillMaxWidth(0.62f)
                    .graphicsLayer {
                        scaleX = scale.value; scaleY = scale.value
                        rotationZ = tilt.value
                    },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Worm",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.graphicsLayer { alpha = textAlpha.value },
            )
            Text(
                "Read it. Hear it. Worm it.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.graphicsLayer { alpha = textAlpha.value },
            )
            Spacer(Modifier.height(28.dp))
            LoadingIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(56.dp)
                    .graphicsLayer { alpha = textAlpha.value },
            )
        }
    }
}

@Composable
fun PermissionScreen(onGrant: () -> Unit) {
    IntroBackdrop {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(painterResource(R.drawable.worm_logo), null, Modifier.fillMaxWidth(0.5f))
            Spacer(Modifier.height(24.dp))
            Text("Let the worm in", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(12.dp))
            Text(
                "Worm reads books and audiobooks right where you keep them. To find them, convert them, " +
                    "and tuck them into the Burrow, it needs All files access. Nothing leaves your phone unless you say so.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            BigButton("Grant access", Icons.Rounded.FolderOpen, onGrant)
        }
    }
}

@Composable
fun BigButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.LargeContainerHeight),
        modifier = modifier.height(ButtonDefaults.LargeContainerHeight),
    ) {
        Icon(icon, null, Modifier.size(ButtonDefaults.iconSizeFor(ButtonDefaults.LargeContainerHeight)))
        Spacer(Modifier.size(ButtonDefaults.iconSpacingFor(ButtonDefaults.LargeContainerHeight)))
        Text(label, style = ButtonDefaults.textStyleFor(ButtonDefaults.LargeContainerHeight))
    }
}

/** First run: pick which folders Worm should read from. */
@Composable
fun OnboardingScreen(
    suggestions: List<File>,
    chosen: List<String>,
    onToggle: (String, Boolean) -> Unit,
    onPickOther: () -> Unit,
    onDone: () -> Unit,
) {
    IntroBackdrop {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Image(painterResource(R.drawable.worm_logo), null, Modifier.size(96.dp))
            Text("Where do your books live?", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(8.dp))
            Text(
                "Point Worm at the folders that hold your books and audiobooks. They stay put; Worm just reads them. " +
                    "You can change this any time in Settings › Library folders.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            val all = (suggestions.map { it.absolutePath } + chosen).distinct()
            all.forEach { path ->
                val on = path in chosen
                Surface(
                    onClick = { onToggle(path, !on) },
                    shape = RoundedCornerShape(22.dp),
                    color = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.8f),
                    contentColor = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (on) Icons.Rounded.CheckCircle else Icons.Rounded.Folder, null)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(File(path).name, style = MaterialTheme.typography.titleMedium)
                            Text(path, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onPickOther, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.CreateNewFolder, null)
                Spacer(Modifier.width(8.dp))
                Text("Choose another folder")
            }
            Spacer(Modifier.height(24.dp))
            BigButton(
                if (chosen.isEmpty()) "Skip for now" else "Start reading",
                Icons.Rounded.CheckCircle, onDone,
                Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Turns a SAF tree URI from the folder picker into a real path (we have All files access). */
fun treeUriToPath(uri: android.net.Uri): String? {
    val docId = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    val (volume, rel) = docId.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
    val base = if (volume.equals("primary", true)) android.os.Environment.getExternalStorageDirectory().absolutePath
    else "/storage/$volume"
    return if (rel.isEmpty()) base else "$base/$rel"
}

@Composable
fun rememberPickedState() = remember { mutableStateOf(listOf<String>()) }
