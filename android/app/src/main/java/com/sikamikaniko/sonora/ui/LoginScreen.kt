package com.sikamikaniko.sonora.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun LoginScreen(vm: SonoraViewModel) {
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val brand = LocalBrandBrush.current
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current

    // Entrance animation — content rises and fades in on first composition.
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { revealed = true }

    Box(modifier = Modifier.fillMaxSize()) {

        // --- Layer 1: full-bleed brand gradient ---------------------------------
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(brand)
        )

        // --- Layer 2: slow, soft animated aurora blobs --------------------------
        AuroraBackdrop(
            colorA = scheme.primary,
            colorB = scheme.tertiary,
            colorC = scheme.secondary
        )

        // --- Layer 3: unifying scrim so the card + text read cleanly -------------
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            scheme.background.copy(alpha = 0.32f),
                            scheme.background.copy(alpha = 0.55f),
                            scheme.background.copy(alpha = 0.82f)
                        )
                    )
                )
        )

        // --- Content ------------------------------------------------------------
        AnimatedVisibility(
            visible = revealed,
            enter = fadeIn(tween(700)) +
                slideInVertically(tween(700, easing = FastOutSlowInEasing)) { it / 8 },
            modifier = Modifier.align(Alignment.Center)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 480.dp)
                    .padding(horizontal = 28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Brand identity: glowing badge with a live equalizer glyph.
                Box(
                    modifier = Modifier
                        .size(104.dp)
                        .shadow(28.dp, CircleShape, spotColor = scheme.primary, ambientColor = scheme.primary)
                        .clip(CircleShape)
                        .background(brand)
                        .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    EqualizerGlyph(color = scheme.onPrimary)
                }

                Spacer(Modifier.height(22.dp))
                Text(
                    "Sonora",
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        fontSize = 40.sp
                    ),
                    color = scheme.onBackground
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Your music, everywhere",
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(34.dp))

                // --- Glass card holding the login form ---------------------------
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(28.dp))
                        .background(scheme.surface.copy(alpha = 0.62f))
                        .border(
                            1.dp,
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.16f),
                                    scheme.outline.copy(alpha = 0.25f)
                                )
                            ),
                            RoundedCornerShape(28.dp)
                        )
                        .padding(22.dp)
                ) {
                    val fieldShape = RoundedCornerShape(16.dp)
                    val fieldColors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = scheme.surfaceVariant.copy(alpha = 0.45f),
                        unfocusedContainerColor = scheme.surfaceVariant.copy(alpha = 0.28f),
                        focusedBorderColor = scheme.primary,
                        unfocusedBorderColor = scheme.outline.copy(alpha = 0.5f),
                        focusedLeadingIconColor = scheme.primary,
                        unfocusedLeadingIconColor = scheme.onSurfaceVariant,
                        focusedLabelColor = scheme.primary,
                        cursorColor = scheme.primary
                    )

                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it; error = null },
                        label = { Text("Server URL") },
                        singleLine = true,
                        shape = fieldShape,
                        colors = fieldColors,
                        leadingIcon = { Icon(Icons.Outlined.Dns, contentDescription = null) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = user,
                        onValueChange = { user = it; error = null },
                        label = { Text("Username") },
                        singleLine = true,
                        shape = fieldShape,
                        colors = fieldColors,
                        leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it; error = null },
                        label = { Text("Password") },
                        singleLine = true,
                        shape = fieldShape,
                        colors = fieldColors,
                        leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                        visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            TextButton(onClick = { showPass = !showPass }) {
                                Text(
                                    if (showPass) "Hide" else "Show",
                                    color = scheme.primary,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    AnimatedVisibility(visible = error != null) {
                        Column {
                            Spacer(Modifier.height(14.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(scheme.error.copy(alpha = 0.12f))
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    error ?: "",
                                    color = scheme.error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(22.dp))

                    val canConnect = !busy && url.isNotBlank() && user.isNotBlank() && pass.isNotBlank()
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    val pressScale by animateFloatAsState(
                        targetValue = if (pressed) 0.97f else 1f,
                        animationSpec = tween(120),
                        label = "connectPress"
                    )
                    val btnShape = RoundedCornerShape(18.dp)

                    Button(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            busy = true
                            error = null
                            vm.login(url, user, pass) { ok, msg ->
                                busy = false
                                if (!ok) error = msg
                            }
                        },
                        enabled = canConnect,
                        shape = btnShape,
                        interactionSource = interaction,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            contentColor = scheme.onPrimary,
                            disabledContentColor = scheme.onSurfaceVariant
                        ),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .scale(pressScale)
                            .clip(btnShape)
                            .background(
                                if (canConnect) brand else SolidColor(scheme.surfaceVariant.copy(alpha = 0.6f))
                            )
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                                color = scheme.onPrimary
                            )
                        } else {
                            Text(
                                "Connect",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                Text(
                    "Subsonic • Navidrome",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                    letterSpacing = 1.5.sp
                )
            }
        }
    }
}

/** A small, live equalizer — five bars breathing at organic offsets. */
@Composable
private fun EqualizerGlyph(color: Color) {
    val transition = rememberInfiniteTransition(label = "eq")
    val durations = listOf(560, 780, 480, 700, 620)
    val phases = listOf(0.35f, 0.65f, 0.25f, 0.75f, 0.45f)
    val bars = durations.mapIndexed { i, d ->
        transition.animateFloat(
            initialValue = phases[i],
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(d, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar$i"
        )
    }
    Canvas(modifier = Modifier.size(48.dp)) {
        val n = bars.size
        val gap = size.width * 0.06f
        val barW = (size.width - gap * (n - 1)) / n
        val radius = CornerRadius(barW / 2f, barW / 2f)
        bars.forEachIndexed { i, anim ->
            val h = size.height * (0.28f + 0.72f * anim.value)
            val x = i * (barW + gap)
            val y = (size.height - h) / 2f
            drawRoundRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(barW, h),
                cornerRadius = radius
            )
        }
    }
}

/** Soft, slowly drifting radial blobs that give the background an aurora shimmer. */
@Composable
private fun AuroraBackdrop(colorA: Color, colorB: Color, colorC: Color) {
    val transition = rememberInfiniteTransition(label = "aurora")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(14000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "drift"
    )
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        fun blob(color: Color, cx: Float, cy: Float, r: Float, alpha: Float) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = r
                ),
                radius = r,
                center = Offset(cx, cy)
            )
        }
        blob(
            colorA,
            cx = w * (0.30f + 0.10f * cos(t)),
            cy = h * (0.24f + 0.06f * sin(t)),
            r = w * 0.85f,
            alpha = 0.45f
        )
        blob(
            colorB,
            cx = w * (0.78f + 0.10f * sin(t * 0.9f)),
            cy = h * (0.34f + 0.07f * cos(t * 1.1f)),
            r = w * 0.75f,
            alpha = 0.40f
        )
        blob(
            colorC,
            cx = w * (0.55f + 0.12f * cos(t * 0.7f + 1.2f)),
            cy = h * (0.72f + 0.06f * sin(t * 0.8f)),
            r = w * 0.9f,
            alpha = 0.35f
        )
    }
}
