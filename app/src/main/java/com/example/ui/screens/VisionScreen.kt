package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.example.ui.SaraUiState
import com.example.ui.VisionAnalysisUiState
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.CyanPlasma
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.HudBorder
import com.example.ui.theme.HudCardSurface
import com.example.ui.theme.HudMidnight
import com.example.ui.theme.SapphirePulse
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VoidObsidian
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VisionScreen(
    uiState: SaraUiState,
    onAnalyzeBitmap: (Bitmap, String) -> Unit,
    onResetVision: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var visionPrompt by rememberSaveable { mutableStateOf("Mere saamne kya hai? Explain clearly.") }

    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    LaunchedEffect(uiState.commandPermissions.camera) {
        hasCameraPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasCameraPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    var selectedPhotoUri by remember { mutableStateOf<Uri?>(null) }
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            selectedPhotoUri = uri
            onResetVision()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(VoidObsidian, HudMidnight, VoidObsidian)))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 720.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "SARA VISION",
                style = MaterialTheme.typography.headlineMedium,
                color = CyanPlasma
            )
            Text(
                text = "Camera Frame Analysis & Zero-Permission Photo Inspector",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = HudCardSurface,
                contentColor = CyanPlasma
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Camera Vision") },
                    icon = { Icon(Icons.Filled.CameraAlt, contentDescription = null) },
                    modifier = Modifier.testTag("tab_camera_vision")
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Photo Analysis") },
                    icon = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null) },
                    modifier = Modifier.testTag("tab_photo_analysis")
                )
            }

            // Question Input & Quick Prompts
            OutlinedTextField(
                value = visionPrompt,
                onValueChange = { visionPrompt = it },
                label = { Text("Ask SARA about the visual") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("vision_prompt_input")
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    "Mere saamne kya hai?",
                    "Is photo mein kya hai?",
                    "Is image mein kitne log hain?",
                    "Is document ko explain karo."
                ).forEach { sample ->
                    FilterChip(
                        selected = visionPrompt == sample,
                        onClick = { visionPrompt = sample },
                        label = { Text(sample, style = MaterialTheme.typography.bodySmall) }
                    )
                }
            }

            if (selectedTab == 0) {
                CameraVisionPanel(
                    hasPermission = hasCameraPermission,
                    isAnalyzing = uiState.visionState is VisionAnalysisUiState.Analyzing,
                    onRequestPermission = {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                    onCaptureFrame = { bitmap ->
                        onAnalyzeBitmap(bitmap, visionPrompt)
                    }
                )
            } else {
                PhotoAnalysisPanel(
                    selectedUri = selectedPhotoUri,
                    isAnalyzing = uiState.visionState is VisionAnalysisUiState.Analyzing,
                    onPickPhoto = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onAnalyzeSelected = {
                        selectedPhotoUri?.let { uri ->
                            val bmp = decodeBitmapFromUri(context, uri)
                            if (bmp != null) {
                                onAnalyzeBitmap(bmp, visionPrompt)
                            }
                        }
                    }
                )
            }

            // Vision Analysis Output Card
            when (val state = uiState.visionState) {
                VisionAnalysisUiState.Idle -> Unit
                VisionAnalysisUiState.Analyzing -> {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = HudCardSurface),
                        border = BorderStroke(1.dp, CyanPlasma.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                color = CyanPlasma,
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.5.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Analyzing visual frame with Gemini Vision...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = CyanPlasma
                            )
                        }
                    }
                }
                is VisionAnalysisUiState.Success -> {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = HudCardSurface),
                        border = BorderStroke(1.dp, EmeraldActive.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "SARA VISUAL ANALYSIS",
                                style = MaterialTheme.typography.labelSmall,
                                color = EmeraldActive
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = state.analysis,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                is VisionAnalysisUiState.Error -> {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CrimsonAlert.copy(alpha = 0.14f)),
                        border = BorderStroke(1.dp, CrimsonAlert),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = CrimsonAlert,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraVisionPanel(
    hasPermission: Boolean,
    isAnalyzing: Boolean,
    onRequestPermission: () -> Unit,
    onCaptureFrame: (Bitmap) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var continuousVisionActive by rememberSaveable { mutableStateOf(false) }

    if (!hasPermission) {
        continuousVisionActive = false
        Card(
            colors = CardDefaults.cardColors(containerColor = HudCardSurface),
            border = BorderStroke(1.dp, HudBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Visibility,
                    contentDescription = null,
                    tint = CyanPlasma,
                    modifier = Modifier.size(36.dp)
                )
                Text(
                    text = "Camera permission is required before SARA can preview or analyze live camera frames.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Button(
                    onClick = onRequestPermission,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyanPlasma,
                        contentColor = VoidObsidian
                    ),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("grant_camera_button")
                ) {
                    Text("Grant Camera Permission")
                }
            }
        }
        return
    }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    val previewView = remember { PreviewView(context) }

    DisposableEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val executor = ContextCompat.getMainExecutor(context)
        cameraProviderFuture.addListener({
            runCatching {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
            }
        }, executor)

        onDispose {
            runCatching {
                cameraProviderFuture.get().unbindAll()
            }
        }
    }

    // Optional Continuous Vision Mode (strictly gated on active camera permission + preview)
    LaunchedEffect(continuousVisionActive, hasPermission) {
        while (continuousVisionActive && hasPermission) {
            delay(6500L)
            if (!isAnalyzing && continuousVisionActive && hasPermission) {
                captureBitmap(context, imageCapture, onCaptureFrame)
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(270.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(VoidObsidian)
        ) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )
            if (continuousVisionActive) {
                Text(
                    text = "CONTINUOUS VISION ACTIVE (6.5s)",
                    style = MaterialTheme.typography.labelSmall,
                    color = EmeraldActive,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .background(VoidObsidian.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Button(
                onClick = { captureBitmap(context, imageCapture, onCaptureFrame) },
                enabled = !isAnalyzing,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyanPlasma,
                    contentColor = VoidObsidian
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("capture_analyze_button")
            ) {
                Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Capture & Analyze")
            }

            Spacer(modifier = Modifier.width(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Auto Scan", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Spacer(modifier = Modifier.width(6.dp))
                Switch(
                    checked = continuousVisionActive,
                    onCheckedChange = { continuousVisionActive = it }
                )
            }
        }
    }
}

@Composable
private fun PhotoAnalysisPanel(
    selectedUri: Uri?,
    isAnalyzing: Boolean,
    onPickPhoto: () -> Unit,
    onAnalyzeSelected: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (selectedUri != null) {
            AsyncImage(
                model = selectedUri,
                contentDescription = "Selected photo for Gemini analysis",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(18.dp))
            )
        } else {
            Card(
                colors = CardDefaults.cardColors(containerColor = HudCardSurface),
                border = BorderStroke(1.dp, HudBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Text(
                        text = "Select a photo using the Android Photo Picker (no storage permission required).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onPickPhoto,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("pick_photo_button")
            ) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Select Photo")
            }

            Button(
                onClick = onAnalyzeSelected,
                enabled = selectedUri != null && !isAnalyzing,
                colors = ButtonDefaults.buttonColors(containerColor = SapphirePulse),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("analyze_photo_button")
            ) {
                Text("Analyze Photo")
            }
        }
    }
}

private fun captureBitmap(
    context: android.content.Context,
    imageCapture: ImageCapture,
    onBitmapReady: (Bitmap) -> Unit
) {
    imageCapture.takePicture(
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = imageProxyToBitmap(image)
                image.close()
                if (bitmap != null) {
                    onBitmapReady(bitmap)
                }
            }

            override fun onError(exception: ImageCaptureException) = Unit
        }
    )
}

private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
    val buffer = image.planes.firstOrNull()?.buffer ?: return null
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val rotation = image.imageInfo.rotationDegrees
    if (rotation == 0) return raw
    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
}

private fun decodeBitmapFromUri(context: android.content.Context, uri: Uri, maxDim: Int = 1600): Bitmap? {
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        var sampleSize = 1
        while ((bounds.outWidth / sampleSize) > maxDim || (bounds.outHeight / sampleSize) > maxDim) {
            sampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, opts)
        }
    }.getOrNull()
}
