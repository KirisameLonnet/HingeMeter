package com.example.hingemeter

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaPlayer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.TextureView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToLong

class MainActivity : AppCompatActivity(), SensorEventListener {
    private lateinit var angleView: AngleView
    private lateinit var videoView: TextureView
    private lateinit var sensorManager: SensorManager
    private var hingeSensor: Sensor? = null
    private var isListening = false
    private var isVideoModeEnabled = false
    private var mediaPlayer: MediaPlayer? = null
    private var videoUri: Uri? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var videoDurationMs = 0L
    private var videoFrameCount = 0
    private var videoFrameDurationMs = 0L
    private var metadataVideoWidth = 0
    private var metadataVideoHeight = 0
    private var metadataVideoRotation = 0
    private var metadataVideoDurationMs = 0L
    private var isVideoPrepared = false
    private var isSeeking = false
    private var lastSeekPositionMs = -1L
    private var lastSeekRequestMs = 0L
    private var pendingSeekMs: Long? = null
    private var pendingTargetFrameIndex: Long? = null
    private var videoSurface: Surface? = null
    private val baseSeekIntervalMs = 30L
    private val maxSeekIntervalMs = 50L
    private val maxFrameStep = 8L
    private val fastStepDivisor = 6L
    private val skipThresholdFrames = 32L
    private val sensorSamplingPeriodUs = 2000
    private val sensorMaxReportLatencyUs = 0
    private val minCutoffHz = 1.5f
    private val betaCutoff = 0.02f
    private val maxPredictionSec = 0.12f
    private val velocityDecayTimeSec = 0.18f
    private val velocityStopTimeoutSec = 0.25f
    private var lastSensorAngleDegrees = 0f
    private var lastSensorTimestampNs = 0L
    private var angularVelocityDegPerSec = 0f
    private var lastAngleDegrees = 0f
    private var targetAngleDegrees = 0f
    private var smoothedAngleDegrees = 0f
    private var targetFrameIndex = 0L
    private var renderFrameIndex = 0L
    private var lastFrameTimeNanos = 0L
    private var isScrubLoopActive = false
    private val videoLogTag = "HingeVideo"
    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        handleVideoScrubFrame(frameTimeNanos)
    }
    private val pickGif = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            angleView.setGif(uri)
        }
    }
    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            prepareVideo(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        angleView = findViewById(R.id.angleView)
        videoView = findViewById(R.id.videoView)
        angleView.setOnRequestGifPicker {
            pickGif.launch("image/gif")
        }
        angleView.setOnRequestVideoPicker {
            pickVideo.launch("video/*")
        }
        angleView.setOnRequestVideoDeletion {
            stopVideoPlayback()
        }
        videoView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateVideoTransform()
        }
        videoView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                startVideoPlayback()
                updateVideoTransform()
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                updateVideoTransform()
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                releaseMediaPlayer()
                videoSurface?.release()
                videoSurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                // No-op
            }
        }
        sensorManager = getSystemService(SensorManager::class.java)
        hingeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        if (hingeSensor == null) {
            angleView.setOverrideText(getString(R.string.angle_unavailable))
        }
    }

    override fun onResume() {
        super.onResume()
        if (hingeSensor != null && !isListening) {
            sensorManager.registerListener(
                this,
                hingeSensor,
                sensorSamplingPeriodUs,
                sensorMaxReportLatencyUs
            )
            isListening = true
        }
    }

    override fun onPause() {
        super.onPause()
        if (isListening) {
            sensorManager.unregisterListener(this)
            isListening = false
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_HINGE_ANGLE) {
            return
        }
        val angle = event.values.firstOrNull() ?: return
        val clampedAngle = angle.coerceIn(0f, 180f)
        val nowNs = event.timestamp
        if (lastSensorTimestampNs > 0L) {
            val dtSec = (nowNs - lastSensorTimestampNs) / 1_000_000_000f
            if (dtSec > 0f) {
                val velocity = (clampedAngle - lastSensorAngleDegrees) / dtSec
                angularVelocityDegPerSec = velocity.coerceIn(-720f, 720f)
            }
        }
        lastSensorTimestampNs = nowNs
        lastSensorAngleDegrees = clampedAngle
        lastAngleDegrees = clampedAngle
        targetAngleDegrees = clampedAngle
        angleView.setOverrideText(null)
        angleView.setAngle(clampedAngle)
        ensureVideoScrubLoop()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }

    private fun prepareVideo(uri: Uri) {
        videoUri = uri
        loadVideoMetadata(uri)
        isVideoModeEnabled = true
        angleView.setVideoModeEnabled(true)
        startVideoPlayback()
    }

    private fun startVideoPlayback() {
        val uri = videoUri ?: return
        if (!videoView.isAvailable) return
        releaseMediaPlayer()
        isVideoPrepared = false
        videoDurationMs = 0L
        isSeeking = false
        lastSeekPositionMs = -1L
        lastSeekRequestMs = 0L
        pendingSeekMs = null
        pendingTargetFrameIndex = null
        targetFrameIndex = 0L
        renderFrameIndex = 0L

        val surfaceTexture = videoView.surfaceTexture ?: return
        if (videoWidth > 0 && videoHeight > 0) {
            surfaceTexture.setDefaultBufferSize(videoWidth, videoHeight)
        }
        if (videoSurface == null) {
            videoSurface = Surface(surfaceTexture)
        }
        val surface = videoSurface ?: return
        val player = MediaPlayer()
        mediaPlayer = player
        try {
            player.setDataSource(this, uri)
            player.setSurface(surface)
            player.setVolume(0f, 0f)
            player.isLooping = false
            player.setOnPreparedListener { mp ->
                videoDurationMs = mp.duration.toLong().coerceAtLeast(0L)
                if (videoDurationMs <= 0L && metadataVideoDurationMs > 0L) {
                    videoDurationMs = metadataVideoDurationMs
                }
                val width = mp.videoWidth
                val height = mp.videoHeight
                if (width > 0 && height > 0) {
                    videoWidth = width
                    videoHeight = height
                }
                if (videoFrameDurationMs <= 0L && videoFrameCount > 0 && videoDurationMs > 0L) {
                    videoFrameDurationMs =
                        (videoDurationMs.toFloat() / videoFrameCount).roundToLong().coerceAtLeast(1L)
                }
                if (videoFrameCount <= 0 && videoFrameDurationMs > 0L && videoDurationMs > 0L) {
                    videoFrameCount =
                        (videoDurationMs.toFloat() / videoFrameDurationMs).roundToLong().toInt()
                }
                isVideoPrepared = true
                smoothedAngleDegrees = lastAngleDegrees
                targetAngleDegrees = lastAngleDegrees
                renderFrameIndex = computeTargetFrameIndex(lastAngleDegrees)
                targetFrameIndex = renderFrameIndex
                pendingTargetFrameIndex = renderFrameIndex
                updateVideoTransform()
                updateVideoPosition(smoothedAngleDegrees)
                ensureVideoScrubLoop()
            }
            player.setOnVideoSizeChangedListener { _, width, height ->
                if (width > 0 && height > 0) {
                    videoWidth = width
                    videoHeight = height
                    updateVideoTransform()
                }
            }
            player.setOnSeekCompleteListener {
                isSeeking = false
                issueSeekIfNeeded()
            }
            player.setOnErrorListener { _, what, extra ->
                Log.e(videoLogTag, "MediaPlayer error what=$what extra=$extra")
                isSeeking = false
                true
            }
            player.prepareAsync()
        } catch (e: Exception) {
            Log.e(videoLogTag, "Failed to prepare MediaPlayer", e)
            releaseMediaPlayer()
        }
    }

    private fun stopVideoPlayback() {
        videoUri = null
        isVideoModeEnabled = false
        isVideoPrepared = false
        videoDurationMs = 0L
        metadataVideoWidth = 0
        metadataVideoHeight = 0
        metadataVideoRotation = 0
        metadataVideoDurationMs = 0L
        videoFrameCount = 0
        videoFrameDurationMs = 0L
        isSeeking = false
        lastSeekPositionMs = -1L
        lastSeekRequestMs = 0L
        pendingSeekMs = null
        pendingTargetFrameIndex = null
        targetFrameIndex = 0L
        renderFrameIndex = 0L
        angularVelocityDegPerSec = 0f
        lastSensorTimestampNs = 0L
        stopVideoScrubLoop()
        releaseMediaPlayer()
        angleView.setVideoModeEnabled(false)
    }

    private fun releaseMediaPlayer() {
        mediaPlayer?.setOnPreparedListener(null)
        mediaPlayer?.setOnVideoSizeChangedListener(null)
        mediaPlayer?.setOnSeekCompleteListener(null)
        mediaPlayer?.setOnErrorListener(null)
        mediaPlayer?.release()
        mediaPlayer = null
        isSeeking = false
        pendingSeekMs = null
        pendingTargetFrameIndex = null
        targetFrameIndex = 0L
        renderFrameIndex = 0L
        stopVideoScrubLoop()
    }

    private fun updateVideoTransform() {
        val surfaceTexture = videoView.surfaceTexture ?: return
        val viewWidth = videoView.width.toFloat()
        val viewHeight = videoView.height.toFloat()
        val videoWidthLocal = videoWidth.toFloat()
        val videoHeightLocal = videoHeight.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f || videoWidthLocal <= 0f || videoHeightLocal <= 0f) {
            return
        }
        surfaceTexture.setDefaultBufferSize(videoWidth, videoHeight)
        val rotation = resolveEffectiveRotation()
        val isRotated = rotation == 90 || rotation == 270
        val contentWidth = if (isRotated) videoHeightLocal else videoWidthLocal
        val contentHeight = if (isRotated) videoWidthLocal else videoHeightLocal
        val inverseScaleX = videoWidthLocal / viewWidth
        val inverseScaleY = videoHeightLocal / viewHeight
        val centerCropScale = maxOf(viewWidth / contentWidth, viewHeight / contentHeight)
        val viewCenterX = viewWidth / 2f
        val viewCenterY = viewHeight / 2f
        val matrix = Matrix()
        matrix.postTranslate(-viewWidth / 2f, -viewHeight / 2f)
        matrix.postScale(inverseScaleX, inverseScaleY)
        if (rotation != 0) {
            matrix.postRotate(rotation.toFloat())
        }
        matrix.postScale(centerCropScale, centerCropScale)
        matrix.postTranslate(viewCenterX, viewCenterY)
        videoView.setTransform(matrix)
        logVideoTransform(
            viewWidth,
            viewHeight,
            videoWidthLocal,
            videoHeightLocal,
            rotation,
            contentWidth,
            contentHeight,
            inverseScaleX,
            inverseScaleY,
            centerCropScale,
            viewCenterX,
            viewCenterY,
            matrix
        )
    }

    private fun updateVideoPosition(angleDegrees: Float) {
        if (!isVideoModeEnabled || !isVideoPrepared) return
        if (videoDurationMs <= 0) return
        val clamped = angleDegrees.coerceIn(0f, 180f)
        if (canUseFrameIndex()) {
            val frameIndex = computeTargetFrameIndex(clamped)
            targetFrameIndex = frameIndex
            pendingTargetFrameIndex = frameIndex
            pendingSeekMs = null
        } else {
            pendingSeekMs = computeTargetMsFromAngle(clamped)
        }
        issueSeekIfNeeded()
    }

    private fun issueSeekIfNeeded() {
        if (!isVideoModeEnabled || !isVideoPrepared) return
        val player = mediaPlayer ?: return
        val now = SystemClock.uptimeMillis()
        if (isSeeking && now - lastSeekRequestMs < maxSeekIntervalMs) {
            return
        }
        val minIntervalMs = resolveSeekIntervalMs()
        if (now - lastSeekRequestMs < minIntervalMs) {
            return
        }
        if (canUseFrameIndex()) {
            val targetIndex = pendingTargetFrameIndex ?: return
            val lastIndex = (videoFrameCount - 1).coerceAtLeast(0).toLong()
            val delta = targetIndex - renderFrameIndex
            val absDelta = abs(delta)
            if (absDelta == 0L) {
                pendingTargetFrameIndex = null
                return
            }
            val skipThreshold = resolveSkipThresholdFrames(lastIndex)
            val nextIndex = if (absDelta >= skipThreshold) {
                targetIndex
            } else {
                val step = resolveFrameStep(absDelta)
                val direction = if (delta > 0L) 1L else -1L
                renderFrameIndex + step * direction
            }
            val clampedIndex = nextIndex.coerceIn(0L, lastIndex)
            val clampedMs = (clampedIndex * videoFrameDurationMs).coerceIn(0L, videoDurationMs)
            val minDeltaMs = videoFrameDurationMs.coerceAtLeast(1L)
            if (lastSeekPositionMs >= 0L && abs(clampedMs - lastSeekPositionMs) < minDeltaMs) {
                pendingTargetFrameIndex = null
                return
            }
            pendingTargetFrameIndex = null
            lastSeekRequestMs = now
            lastSeekPositionMs = clampedMs
            renderFrameIndex = clampedIndex
            isSeeking = true
            player.seekTo(clampedMs, MediaPlayer.SEEK_CLOSEST)
            return
        }

        val targetMs = pendingSeekMs ?: return
        val clampedMs = targetMs.coerceIn(0L, videoDurationMs)
        val minDeltaMs = videoFrameDurationMs.coerceAtLeast(1L)
        if (lastSeekPositionMs >= 0L && abs(clampedMs - lastSeekPositionMs) < minDeltaMs) {
            pendingSeekMs = null
            return
        }
        pendingSeekMs = null
        lastSeekRequestMs = now
        lastSeekPositionMs = clampedMs
        isSeeking = true
        player.seekTo(clampedMs, MediaPlayer.SEEK_CLOSEST)
    }

    private fun resolveSeekIntervalMs(): Long {
        val speed = abs(angularVelocityDegPerSec)
        if (speed <= 0f) return baseSeekIntervalMs
        val normalized = (speed / 180f).coerceIn(0f, 2.5f)
        val interval = baseSeekIntervalMs + (normalized * 24f).roundToLong()
        return interval.coerceIn(baseSeekIntervalMs, maxSeekIntervalMs)
    }

    private fun resolveFrameStep(absDelta: Long): Long {
        val base = 1L + (absDelta / fastStepDivisor).coerceAtLeast(0L)
        val speedBoost = (abs(angularVelocityDegPerSec) / 120f).coerceIn(0f, 1.5f)
        val boosted = (base * (1f + 0.5f * speedBoost)).roundToLong()
        return boosted.coerceIn(1L, maxFrameStep)
    }

    private fun resolveSkipThresholdFrames(lastIndex: Long): Long {
        val speedBoost = (abs(angularVelocityDegPerSec) / 90f).coerceIn(0f, 2f)
        val threshold = skipThresholdFrames + (speedBoost * 12f).roundToLong()
        val maxThreshold = lastIndex.coerceAtLeast(1L)
        return threshold.coerceIn(1L, maxThreshold)
    }

    private fun canUseFrameIndex(): Boolean {
        return videoFrameCount > 0 && videoFrameDurationMs > 0L
    }

    private fun computeTargetFrameIndex(angleDegrees: Float): Long {
        if (!canUseFrameIndex()) return 0L
        val normalized = angleDegrees.coerceIn(0f, 180f)
        val lastIndex = (videoFrameCount - 1).coerceAtLeast(0)
        return ((normalized / 180f) * lastIndex).roundToLong().coerceIn(0L, lastIndex.toLong())
    }

    private fun computeTargetMsFromAngle(angleDegrees: Float): Long {
        val normalized = angleDegrees.coerceIn(0f, 180f)
        return (videoDurationMs.toFloat() * (normalized / 180f)).roundToLong()
    }

    private fun loadVideoMetadata(uri: Uri) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            val width = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )?.toIntOrNull() ?: 0
            val duration = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            val frameCount = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT
            )?.toIntOrNull() ?: 0
            val captureRate = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE
            )?.toFloatOrNull()
            if (width > 0 && height > 0) {
                metadataVideoWidth = width
                metadataVideoHeight = height
                videoWidth = width
                videoHeight = height
            }
            metadataVideoRotation = rotation
            metadataVideoDurationMs = duration
            if (frameCount > 0) {
                videoFrameCount = frameCount
            }
            if (captureRate != null && captureRate > 0f) {
                videoFrameDurationMs = (1000f / captureRate).roundToLong().coerceAtLeast(1L)
            }
            if (videoFrameCount <= 0 && captureRate != null && captureRate > 0f && duration > 0L) {
                videoFrameCount =
                    ((duration / 1000f) * captureRate).roundToLong().toInt()
            }
            if (videoFrameDurationMs <= 0L && videoFrameCount > 0 && duration > 0L) {
                videoFrameDurationMs =
                    (duration.toFloat() / videoFrameCount).roundToLong().coerceAtLeast(1L)
            }
            Log.d(
                videoLogTag,
                "metadata width=$metadataVideoWidth height=$metadataVideoHeight " +
                    "rotation=$metadataVideoRotation durationMs=$metadataVideoDurationMs " +
                    "frameCount=$videoFrameCount frameMs=$videoFrameDurationMs"
            )
        } catch (_: Exception) {
            metadataVideoRotation = 0
        } finally {
            retriever.release()
        }
    }

    private fun resolveEffectiveRotation(): Int {
        val normalized = ((metadataVideoRotation % 360) + 360) % 360
        if (normalized != 90 && normalized != 270) {
            return normalized
        }
        if (metadataVideoWidth <= 0 || metadataVideoHeight <= 0) {
            return normalized
        }
        if (videoWidth <= 0 || videoHeight <= 0) {
            return normalized
        }
        val metaDisplayWidth =
            if (normalized == 90 || normalized == 270) metadataVideoHeight else metadataVideoWidth
        val metaDisplayHeight =
            if (normalized == 90 || normalized == 270) metadataVideoWidth else metadataVideoHeight
        val rotationApplied = videoWidth == metaDisplayWidth && videoHeight == metaDisplayHeight
        return if (rotationApplied) 0 else normalized
    }

    private fun logVideoTransform(
        viewWidth: Float,
        viewHeight: Float,
        videoWidth: Float,
        videoHeight: Float,
        rotation: Int,
        contentWidth: Float,
        contentHeight: Float,
        inverseScaleX: Float,
        inverseScaleY: Float,
        centerCropScale: Float,
        centerX: Float,
        centerY: Float,
        matrix: Matrix
    ) {
        val values = FloatArray(9)
        matrix.getValues(values)
        Log.d(
            videoLogTag,
            "transform view=(${viewWidth}x${viewHeight}) " +
                "video=(${videoWidth}x${videoHeight}) " +
                "content=(${contentWidth}x${contentHeight}) " +
                "rotation=$rotation inverse=($inverseScaleX,$inverseScaleY) " +
                "scale=$centerCropScale center=($centerX,$centerY) " +
                "matrix=${values.contentToString()}"
        )
    }

    private fun ensureVideoScrubLoop() {
        if (!isVideoModeEnabled || !isVideoPrepared) return
        if (isScrubLoopActive) return
        isScrubLoopActive = true
        lastFrameTimeNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopVideoScrubLoop() {
        if (!isScrubLoopActive) return
        isScrubLoopActive = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private fun handleVideoScrubFrame(frameTimeNanos: Long) {
        if (!isVideoModeEnabled || !isVideoPrepared || mediaPlayer == null) {
            isScrubLoopActive = false
            return
        }
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val dtMs = if (lastFrameTimeNanos == 0L) {
            0f
        } else {
            (nowNs - lastFrameTimeNanos) / 1_000_000f
        }
        lastFrameTimeNanos = nowNs
        val predictedAngle = predictAngle(nowNs)
        if (dtMs > 0f) {
            val dtSec = dtMs / 1000f
            val cutoff = minCutoffHz + betaCutoff * abs(angularVelocityDegPerSec)
            val alpha = computeAlpha(cutoff, dtSec)
            smoothedAngleDegrees += (predictedAngle - smoothedAngleDegrees) * alpha
        } else {
            smoothedAngleDegrees = predictedAngle
        }
        updateVideoPosition(smoothedAngleDegrees)
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun predictAngle(frameTimeNanos: Long): Float {
        if (lastSensorTimestampNs == 0L) {
            return targetAngleDegrees.coerceIn(0f, 180f)
        }
        val dtSec =
            (frameTimeNanos - lastSensorTimestampNs).coerceAtLeast(0L) / 1_000_000_000f
        if (dtSec >= velocityStopTimeoutSec) {
            angularVelocityDegPerSec = 0f
            return lastSensorAngleDegrees.coerceIn(0f, 180f)
        }
        val decay = exp(-dtSec / velocityDecayTimeSec)
        val effectiveVelocity = angularVelocityDegPerSec * decay
        val predictionWindow = dtSec.coerceAtMost(maxPredictionSec)
        val predicted =
            lastSensorAngleDegrees + effectiveVelocity * predictionWindow
        return predicted.coerceIn(0f, 180f)
    }

    private fun computeAlpha(cutoffHz: Float, dtSec: Float): Float {
        if (cutoffHz <= 0f || dtSec <= 0f) return 1f
        val tau = 1f / (2f * Math.PI.toFloat() * cutoffHz)
        return 1f / (1f + tau / dtSec)
    }
}
