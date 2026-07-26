package com.example.finevolume

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.finevolume.audio.AudioGainManager
import com.example.finevolume.audio.CurveMode
import com.example.finevolume.service.VolumeAccessibilityService

class MainActivity : ComponentActivity() {

    private lateinit var audioGainManager: AudioGainManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioGainManager = AudioGainManager(this)

        setContent {
            FineVolumeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    FineVolumeScreen(audioGainManager)
                }
            }
        }
    }
}

@Composable
fun FineVolumeTheme(content: @Composable () -> Unit) {
    val darkColors = darkColorScheme(
        primary = Color(0xFF6C63FF),
        secondary = Color(0xFF00E676),
        background = Color(0xFF0F111A),
        surface = Color(0xFF1A1D2E),
        onPrimary = Color.White,
        onSurface = Color.White
    )
    MaterialTheme(
        colorScheme = darkColors,
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FineVolumeScreen(audioGainManager: AudioGainManager) {
    val context = LocalContext.current
    var maxSteps by remember { mutableStateOf(audioGainManager.maxSteps) }
    var currentStep by remember { mutableStateOf(audioGainManager.currentStep) }
    var selectedCurve by remember { mutableStateOf(audioGainManager.curveMode) }
    var perDeviceMemory by remember { mutableStateOf(audioGainManager.perDeviceMemoryEnabled) }
    
    var isAccessibilityEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    DisposableEffect(audioGainManager) {
        val prefs = context.getSharedPreferences("fine_volume_prefs", Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            currentStep = audioGainManager.currentStep
            maxSteps = audioGainManager.maxSteps
            perDeviceMemory = audioGainManager.perDeviceMemoryEnabled
            selectedCurve = audioGainManager.curveMode
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    var showCustomDialog by remember { mutableStateOf(false) }
    var customInputText by remember { mutableStateOf("") }

    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "FineVolume",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color(0xFF151824)
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color(0xFF0F111A))
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Status Card
            StatusCard(
                isAccessibilityEnabled = isAccessibilityEnabled,
                canDrawOverlays = canDrawOverlays,
                maxSteps = maxSteps,
                onRefresh = {
                    isAccessibilityEnabled = isAccessibilityServiceEnabled(context)
                    canDrawOverlays = Settings.canDrawOverlays(context)
                }
            )

            // Current Volume Dashboard Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1D2E)),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Active Device Badge
                    val activeDeviceName = audioGainManager.getActiveDeviceDisplayName()
                    Surface(
                        color = Color(0xFF252A40),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        Text(
                            text = "Active Output: $activeDeviceName",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFFFB74D),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }

                    Text(
                        "Current Fine Volume",
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                    
                    val percent = (audioGainManager.getGainFraction(currentStep) * 100).toInt()
                    Text(
                        text = "$percent%",
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00E676)
                    )

                    Text(
                        text = "Step $currentStep of $maxSteps",
                        fontSize = 16.sp,
                        color = Color.LightGray
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Slider(
                        value = currentStep.toFloat(),
                        onValueChange = { newValue ->
                            val step = newValue.toInt()
                            currentStep = step
                            audioGainManager.currentStep = step
                        },
                        valueRange = 0f..maxSteps.toFloat(),
                        steps = maxSteps.coerceAtMost(100),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF00E676),
                            activeTrackColor = Color(0xFF6C63FF)
                        )
                    )

                    // Quick Preset Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        listOf(10, 25, 50, 75, 100).forEach { presetPercent ->
                            FilterChip(
                                selected = false,
                                onClick = {
                                    val targetStep = (maxSteps * (presetPercent / 100f)).toInt()
                                    currentStep = targetStep
                                    audioGainManager.currentStep = targetStep
                                },
                                label = { Text("$presetPercent%") }
                            )
                        }
                    }
                }
            }

            // Step Count & Curve Configuration
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1D2E)),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        "Settings & Customization",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    // Per-Device Volume Memory Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Per-Device Volume Memory",
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                fontSize = 14.sp
                            )
                            Text(
                                "Remembers separate fine volume steps for Bluetooth headphones, wired headsets, and phone speakers.",
                                fontSize = 11.sp,
                                color = Color.Gray
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = perDeviceMemory,
                            onCheckedChange = { checked ->
                                perDeviceMemory = checked
                                audioGainManager.perDeviceMemoryEnabled = checked
                                currentStep = audioGainManager.currentStep
                            }
                        )
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

                    // Custom Step Count Selection
                    Text(
                        "Total Volume Steps: $maxSteps steps",
                        fontWeight = FontWeight.SemiBold,
                        color = Color.LightGray
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val presetOptions = listOf(30, 50, 100, 150, 200)
                        presetOptions.forEach { stepOption ->
                            FilterChip(
                                selected = (maxSteps == stepOption),
                                onClick = {
                                    audioGainManager.maxSteps = stepOption
                                    maxSteps = audioGainManager.maxSteps
                                    currentStep = audioGainManager.currentStep
                                },
                                label = {
                                    Text(
                                        "$stepOption steps",
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            )
                        }

                        if (!presetOptions.contains(maxSteps)) {
                            FilterChip(
                                selected = true,
                                onClick = {
                                    customInputText = maxSteps.toString()
                                    showCustomDialog = true
                                },
                                label = {
                                    Text(
                                        "$maxSteps steps (Custom)",
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            )
                        }
                    }

                    // Custom Step Count Button
                    OutlinedButton(
                        onClick = {
                            customInputText = maxSteps.toString()
                            showCustomDialog = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFFFF9100)
                        )
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Set Custom Step Count...", fontSize = 14.sp)
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

                    // Volume Curve Mode
                    Text(
                        "Volume Curve Profile",
                        fontWeight = FontWeight.SemiBold,
                        color = Color.LightGray
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = (selectedCurve == CurveMode.LOW_RANGE_FINE),
                            onClick = {
                                selectedCurve = CurveMode.LOW_RANGE_FINE
                                audioGainManager.curveMode = CurveMode.LOW_RANGE_FINE
                            },
                            label = {
                                Text(
                                    "Preset Low-Range Fine (0-30%)",
                                    maxLines = 1,
                                    softWrap = false
                                )
                            },
                            leadingIcon = {
                                if (selectedCurve == CurveMode.LOW_RANGE_FINE) {
                                    Icon(Icons.Default.Check, contentDescription = null)
                                }
                            }
                        )

                        FilterChip(
                            selected = (selectedCurve == CurveMode.LINEAR),
                            onClick = {
                                selectedCurve = CurveMode.LINEAR
                                audioGainManager.curveMode = CurveMode.LINEAR
                            },
                            label = {
                                Text(
                                    "Linear",
                                    maxLines = 1,
                                    softWrap = false
                                )
                            },
                            leadingIcon = {
                                if (selectedCurve == CurveMode.LINEAR) {
                                    Icon(Icons.Default.Check, contentDescription = null)
                                }
                            }
                        )
                    }

                    Text(
                        text = if (selectedCurve == CurveMode.LOW_RANGE_FINE)
                            "★ Recommended Profile: Provides ultra-fine increments in the lower volume range so quiet audio is never too loud."
                        else
                            "Provides uniform step sizing across all volume levels.",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
            }

            // Setup & Permissions Guide
            PermissionsCard(
                isAccessibilityEnabled = isAccessibilityEnabled,
                canDrawOverlays = canDrawOverlays,
                context = context
            )
        }
    }

    if (showCustomDialog) {
        AlertDialog(
            onDismissRequest = { showCustomDialog = false },
            title = { Text("Custom Volume Steps") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter total number of volume steps (10 to 200):", fontSize = 14.sp)
                    OutlinedTextField(
                        value = customInputText,
                        onValueChange = { input -> customInputText = input.filter { char -> char.isDigit() } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val inputInt = customInputText.toIntOrNull()
                        if (inputInt != null) {
                            val clamped = inputInt.coerceIn(10, 200)
                            audioGainManager.maxSteps = clamped
                            maxSteps = audioGainManager.maxSteps
                            currentStep = audioGainManager.currentStep
                        }
                        showCustomDialog = false
                    }
                ) {
                    Text("Apply Steps")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun StatusCard(
    isAccessibilityEnabled: Boolean,
    canDrawOverlays: Boolean,
    maxSteps: Int,
    onRefresh: () -> Unit
) {
    val isFullyReady = isAccessibilityEnabled && canDrawOverlays
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isFullyReady) Color(0xFF1B382B) else Color(0xFF38231B)
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isFullyReady) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (isFullyReady) Color(0xFF00E676) else Color(0xFFFF9100),
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = if (isFullyReady) "FineVolume Active" else "Setup Required",
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = if (isFullyReady) "Hardware keys are mapped to $maxSteps fine steps" else "Grant permissions below to enable",
                        fontSize = 12.sp,
                        color = Color.LightGray
                    )
                }
            }

            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color.White)
            }
        }
    }
}

@Composable
fun PermissionsCard(
    isAccessibilityEnabled: Boolean,
    canDrawOverlays: Boolean,
    context: Context
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1D2E)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Android Setup Guide",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            // Step 1: Accessibility
            PermissionRow(
                title = "1. Enable Accessibility Service",
                subtitle = "Required to intercept hardware volume button presses.",
                isGranted = isAccessibilityEnabled,
                onButtonClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            )

            // Restricted Settings Tip Box
            if (!isAccessibilityEnabled) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1F1D)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = Color(0xFFFFB74D),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Getting 'Restricted Setting' or 'App Denied'?",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFFB74D),
                                fontSize = 13.sp
                            )
                        }
                        Text(
                            "Android 13+ blocks Accessibility for sideloaded APKs until allowed:\n" +
                                    "1. Tap 'Open App Info' below.\n" +
                                    "2. Tap the 3 dots (⋮) in the top-right corner.\n" +
                                    "3. Select 'Allow restricted settings'.\n" +
                                    "4. Return here and tap 'Enable' above!",
                            fontSize = 11.sp,
                            color = Color.LightGray
                        )
                        Button(
                            onClick = {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD84315)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Open App Info Settings", fontSize = 12.sp)
                        }
                    }
                }
            }

            // Step 2: Overlay Permission
            PermissionRow(
                title = "2. Display Over Other Apps",
                subtitle = "Required for the floating volume overlay slider.",
                isGranted = canDrawOverlays,
                onButtonClick = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(intent)
                }
            )

            // Step 3: Background Battery Guide
            PermissionRow(
                title = "3. Disable Battery Optimization",
                subtitle = "Prevents system battery saver from stopping FineVolume in the background.",
                isGranted = true,
                buttonText = "Open Battery Settings",
                onButtonClick = {
                    try {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        context.startActivity(Intent(Settings.ACTION_SETTINGS))
                    }
                }
            )
        }
    }
}

@Composable
fun PermissionRow(
    title: String,
    subtitle: String,
    isGranted: Boolean,
    buttonText: String = if (isGranted) "Enabled" else "Enable",
    onButtonClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF151824))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 14.sp)
            Text(subtitle, color = Color.Gray, fontSize = 11.sp)
        }

        Spacer(modifier = Modifier.width(8.dp))

        Button(
            onClick = onButtonClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isGranted) Color(0xFF2E7D32) else Color(0xFF6C63FF)
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(buttonText, fontSize = 12.sp)
        }
    }
}

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expectedService = "${context.packageName}/${VolumeAccessibilityService::class.java.canonicalName}"
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    return enabledServices.contains(expectedService)
}
