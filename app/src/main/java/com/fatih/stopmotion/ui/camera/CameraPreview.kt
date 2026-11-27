@file:OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)

package com.fatih.stopmotion.ui.camera

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import android.util.Size
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import com.fatih.stopmotion.fx.ChromaState
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private suspend fun Context.awaitCameraProvider(): ProcessCameraProvider =
    suspendCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                try {
                    cont.resume(future.get())
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

/** Kamera yang sedang terpasang beserta kemampuannya. */
private class BoundCamera(val camera: androidx.camera.core.Camera, val caps: CameraCaps)

/** Membaca kemampuan kamera: rentang shutter/ISO, fokus, white balance, kompensasi eksposur, dan zoom. */
private fun readCaps(camera: androidx.camera.core.Camera): CameraCaps = runCatching {
    val info = Camera2CameraInfo.from(camera.cameraInfo)
    fun <T> chr(key: CameraCharacteristics.Key<T>): T? = runCatching { info.getCameraCharacteristic(key) }.getOrNull()
    val exposure = chr(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
    val iso = chr(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
    val aeModes = chr(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
    val capabilities = chr(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
    val manual = exposure != null && iso != null &&
        aeModes?.contains(CameraMetadata.CONTROL_AE_MODE_OFF) == true &&
        capabilities?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
    val ev = camera.cameraInfo.exposureState
    val evOk = ev.isExposureCompensationSupported
    CameraCaps(
        minExposureNs = exposure?.lower ?: 0L,
        maxExposureNs = exposure?.upper ?: 0L,
        minIso = iso?.lower ?: 0,
        maxIso = iso?.upper ?: 0,
        minFocusDiopter = chr(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
        awbModes = chr(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toSet() ?: setOf(Awb.MODE_AUTO),
        awbLockSupported = chr(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) ?: false,
        manualExposure = manual,
        evMin = if (evOk) ev.exposureCompensationRange.lower else 0,
        evMax = if (evOk) ev.exposureCompensationRange.upper else 0,
        evStepStops = if (evOk) ev.exposureCompensationStep.toFloat() else 0f,
        maxZoom = camera.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
    )
}.getOrDefault(CameraCaps())

/**
 * Menerapkan rencana kontrol ke kamera. Opsi Camera2 diganti seluruhnya setiap kali (bukan ditambah), jadi
 * pengaturan yang dilepas (mis. kembali ke auto focus) otomatis dikembalikan ke perilaku bawaan CameraX.
 */
private fun applyPlan(camera: androidx.camera.core.Camera, plan: RequestPlan) {
    runCatching {
        val b = CaptureRequestOptions.Builder()
        if (plan.afOff) b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        plan.focusDiopter?.let { b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, it) }
        if (plan.manualExposure) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, plan.exposureNs)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, plan.iso)
        } else if (plan.aeLock) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
        }
        b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, plan.awbMode)
        if (plan.awbLock) b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
        Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(b.build())
    }
    runCatching { camera.cameraControl.setZoomRatio(plan.zoom) }
    runCatching {
        if (camera.cameraInfo.exposureState.isExposureCompensationSupported) {
            camera.cameraControl.setExposureCompensationIndex(plan.evIndex)
        }
    }
}

/**
 * Live preview CameraX + use case ImageCapture.
 * Mode COMPATIBLE (TextureView) dipakai agar overlay onion skin (Tahap 2)
 * bisa digambar di atas preview dengan transparansi.
 */
@Composable
fun CameraPreview(
    lensFacing: Int,
    flashMode: Int,
    captureQuality: CaptureQuality,
    /** Bila aktif, setiap frame pratinjau diproses chroma key dan dikirim lewat onLiveFrame. */
    chroma: ChromaState,
    /** Fokus, white balance, eksposur, zoom, shutter, ISO. */
    adjust: CameraAdjust,
    /** Dipanggil setiap kamera selesai dipasang, dengan kemampuan kamera itu. */
    onCaps: (CameraCaps) -> Unit,
    /** Nilai fokus/shutter/ISO yang sedang dipakai kamera (dilaporkan paling cepat tiap 0,3 detik, hanya bila berubah). */
    onMeasured: (Measured) -> Unit,
    onLiveFrame: (android.graphics.Bitmap?) -> Unit,
    onReady: (imageCapture: ImageCapture, hasFlash: Boolean) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnReady by rememberUpdatedState(onReady)
    val currentOnError by rememberUpdatedState(onError)
    val currentOnLive by rememberUpdatedState(onLiveFrame)
    val currentOnCaps by rememberUpdatedState(onCaps)
    val currentOnMeasured by rememberUpdatedState(onMeasured)

    // Nilai terakhir yang dipakai kamera, diisi dari hasil tiap frame (thread kamera), dibaca di sini.
    val measuredRef = remember { AtomicReference(Measured()) }
    val measureCallback = remember {
        object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                measuredRef.set(
                    Measured(
                        focusDiopter = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: -1f,
                        exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
                        iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
                    )
                )
            }
        }
    }
    var bound by remember { mutableStateOf<BoundCamera?>(null) }

    // Pengaturan terbaru dibaca analyzer tanpa memasang ulang kamera setiap slider digeser.
    val chromaRef = remember { AtomicReference(chroma) }
    SideEffect { chromaRef.set(chroma) }
    val live = chroma.active
    val mirror = lensFacing == CameraSelector.LENS_FACING_FRONT
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { analysisExecutor.shutdown() } }

    val resolutionSelector = remember {
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
    }
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    // Ukuran foto mengikuti pilihan kualitas; pratinjau tetap memakai pemilih bawaan.
    val captureSelector = remember(captureQuality) {
        val builder = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        if (captureQuality.hasTarget) {
            builder.setResolutionStrategy(
                ResolutionStrategy(
                    Size(captureQuality.width, captureQuality.height),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
        }
        builder.build()
    }
    val imageCapture = remember(captureQuality) {
        ImageCapture.Builder()
            .setResolutionSelector(captureSelector)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    val providerRef = remember { arrayOfNulls<ProcessCameraProvider>(1) }

    LaunchedEffect(flashMode, imageCapture) { imageCapture.flashMode = flashMode }

    LaunchedEffect(lensFacing, captureQuality, live) {
        try {
            val provider = context.awaitCameraProvider()
            providerRef[0] = provider
            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            if (!provider.hasCamera(selector)) {
                currentOnError("Kamera ini tidak tersedia di perangkat.")
                return@LaunchedEffect
            }
            val previewBuilder = Preview.Builder().setResolutionSelector(resolutionSelector)
            Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(measureCallback)
            val preview = previewBuilder.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

            bound = null
            provider.unbindAll()
            var camera: androidx.camera.core.Camera? = null
            if (live) {
                // Analisis kecil (sekitar 640x480) agar chroma key tetap lancar saat membidik.
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(640, 480),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                                )
                            )
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                val mainExecutor = ContextCompat.getMainExecutor(context)
                analysis.setAnalyzer(analysisExecutor) { proxy ->
                    try {
                        val state = chromaRef.get()
                        if (state.active) {
                            val bmp = LiveChroma.process(proxy, state, mirror)
                            mainExecutor.execute { currentOnLive(bmp) }
                        }
                    } catch (e: Throwable) {
                        // frame dilewati; pratinjau biasa tetap tampil
                    } finally {
                        proxy.close()
                    }
                }
                camera = try {
                    provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture, analysis)
                } catch (e: IllegalArgumentException) {
                    // Kombinasi use case tidak didukung kamera ini: tetap bisa memotret, tanpa pratinjau chroma.
                    provider.unbindAll()
                    currentOnLive(null)
                    currentOnError("Pratinjau chroma langsung tidak didukung kamera ini. Latar tetap dihapus saat edit dan ekspor.")
                    null
                }
            }
            if (camera == null) camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
            currentOnReady(imageCapture, camera.cameraInfo.hasFlashUnit())
            val caps = readCaps(camera)
            currentOnCaps(caps)
            bound = BoundCamera(camera, caps)
        } catch (e: Exception) {
            currentOnError("Kamera gagal dibuka: ${e.message}")
        }
    }

    // Terapkan pengaturan manual. Dicek tiap 0,3 dtk karena bagian eksposur yang masih otomatis mengikuti
    // nilai terakhir kamera; kamera hanya disentuh bila rencananya benar-benar berubah.
    LaunchedEffect(bound, adjust) {
        val b = bound ?: return@LaunchedEffect
        var last: RequestPlan? = null
        while (isActive) {
            val plan = adjust.plan(b.caps, measuredRef.get())
            if (plan != last) {
                applyPlan(b.camera, plan)
                last = plan
            }
            delay(300)
        }
    }
    LaunchedEffect(Unit) {
        var last = Measured()
        while (isActive) {
            delay(300)
            val m = measuredRef.get()
            if (m != last) {
                last = m
                currentOnMeasured(m)
            }
        }
    }

    // Saat chroma dimatikan, buang frame terakhir agar pratinjau normal kembali tampil.
    LaunchedEffect(live) { if (!live) currentOnLive(null) }

    // Lepas kamera saat meninggalkan halaman (navigasi tidak menghancurkan Activity).
    DisposableEffect(Unit) {
        onDispose { runCatching { providerRef[0]?.unbindAll() } }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}
