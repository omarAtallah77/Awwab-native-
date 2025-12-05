import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.core.Preview
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleOwner
import com.example.awwab.pose_detections.PoseAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraComposeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (!granted) finish()
        }

        permissionLauncher.launch(Manifest.permission.CAMERA)

        setContent {
            CameraScreen()
        }
    }
}

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Compose UI states
    var poseText by remember { mutableStateOf("Detecting...") }
    var poseColor by remember { mutableStateOf(Color.White) }
    var cameraSelector by remember { mutableStateOf(CameraSelector.DEFAULT_BACK_CAMERA) }

    // Hold references to previewView and cameraProvider so we can re-bind when cameraSelector changes
    val previewViewState = remember { mutableStateOf<PreviewView?>(null) }
    val cameraProviderState = remember { mutableStateOf<ProcessCameraProvider?>(null) }

    // Create analyzer ONCE
    val analyzer = remember {
        PoseAnalyzer(context) { pose, color, _points ->
            // The PoseAnalyzer callback provides pose label, color int, and a list of key points.
            // We only need label and color for the UI overlay here; ignore the points for now.
            poseText = pose
            poseColor = Color(color)
        }
    }

    // Dedicated executor for image analysis (remember so we can shut it down)
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }

    // Init model off the main thread to avoid jank
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) {
            try {
                analyzer.initialize()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Dispose analyzer and shutdown executor when leaving the screen
    DisposableEffect(Unit) {
        onDispose {
            try {
                analyzer.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            try {
                analyzerExecutor.shutdown()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Helper to bind camera safely
    fun bindCamera(
        cameraProvider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        analyzer: ImageAnalysis.Analyzer,
        cameraSelector: CameraSelector,
        executor: ExecutorService
    ) {
        val preview = Preview.Builder().build().apply {
            setSurfaceProvider(previewView.surfaceProvider)
        }

        val imageAnalyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalyzer.setAnalyzer(executor, analyzer)

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalyzer
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)

                // Save previewView reference to state so we can rebind later
                previewViewState.value = previewView

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    try {
                        val cameraProvider = cameraProviderFuture.get()
                        cameraProviderState.value = cameraProvider

                        // Bind initial camera once provider is ready
                        previewViewState.value?.let { pv ->
                            bindCamera(
                                cameraProvider,
                                lifecycleOwner,
                                pv,
                                analyzer,
                                cameraSelector,
                                analyzerExecutor
                            )
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            update = { view ->
                // Re-bind when cameraSelector or provider changes
                val provider = cameraProviderState.value
                if (provider != null) {
                    bindCamera(
                        provider,
                        lifecycleOwner,
                        view,
                        analyzer,
                        cameraSelector,
                        analyzerExecutor
                    )
                }
            }
        )

        // UI Overlay
        Text(
            text = poseText,
            color = poseColor,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp),
            style = MaterialTheme.typography.headlineMedium
        )

        Button(
            onClick = {
                cameraSelector =
                    if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA)
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    else
                        CameraSelector.DEFAULT_BACK_CAMERA
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Text("Flip")
        }
    }
}
