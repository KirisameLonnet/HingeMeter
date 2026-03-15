package com.example.hingemeter

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

class VideoFrameExtractor(
    private val context: Context,
    private val uri: Uri,
    private val surface: Surface
) {
    private var extractor: MediaExtractor? = null
    private var decoder: MediaCodec? = null
    private var videoTrackIndex = -1
    private var isPlaying = false
    private var requestCounter = 0
    @Volatile private var latestRequestId = 0

    private val handlerThread = HandlerThread("VideoExtractorThread").apply { start() }
    private val handler = Handler(handlerThread.looper)

    var videoWidth = 0
        private set
    var videoHeight = 0
        private set
    var durationMs = 0L
        private set
    var frameRate = 30f
        private set
    var keyframeTimesMs = LongArray(0)
        private set
    var frameCount = 0
        private set
    var avgFrameDurationMs = 0L
        private set

    fun prepare(onPrepared: (Boolean) -> Unit) {
        handler.post {
            try {
                extractor = MediaExtractor().apply {
                    setDataSource(context, uri, null)
                }

                for (i in 0 until (extractor?.trackCount ?: 0)) {
                    val format = extractor?.getTrackFormat(i)
                    val mime = format?.getString(MediaFormat.KEY_MIME)
                    if (mime?.startsWith("video/") == true) {
                        videoTrackIndex = i
                        extractor?.selectTrack(i)

                        videoWidth = format.getInteger(MediaFormat.KEY_WIDTH)
                        videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
                        durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000L
                        if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                            frameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                        }
                        keyframeTimesMs = collectKeyframeTimesMs()

                        decoder = MediaCodec.createDecoderByType(mime).apply {
                            configure(format, surface, null, 0)
                            start()
                        }
                        isPlaying = true
                        onPrepared(true)
                        return@post
                    }
                }
                onPrepared(false)
            } catch (e: Exception) {
                Log.e("VideoFrameExtractor", "Error preparing extractor", e)
                onPrepared(false)
            }
        }
    }

    private fun collectKeyframeTimesMs(): LongArray {
        val keyframes = ArrayList<Long>()
        val extractorLocal = MediaExtractor()
        var lastSampleTimeUs = -1L
        var deltaSumUs = 0L
        var deltaCount = 0
        var minDeltaUs = Long.MAX_VALUE
        var maxDeltaUs = 0L
        var sampleCount = 0
        try {
            extractorLocal.setDataSource(context, uri, null)
            var videoTrack = -1
            for (i in 0 until extractorLocal.trackCount) {
                val format = extractorLocal.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("video/") == true) {
                    videoTrack = i
                    break
                }
            }
            if (videoTrack < 0) {
                frameCount = 0
                avgFrameDurationMs = 0L
                return longArrayOf(0L)
            }
            extractorLocal.selectTrack(videoTrack)
            while (true) {
                val timeUs = extractorLocal.sampleTime
                if (timeUs < 0) break
                if (extractorLocal.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    keyframes.add(timeUs / 1000L)
                }
                if (lastSampleTimeUs >= 0L) {
                    val deltaUs = (timeUs - lastSampleTimeUs).coerceAtLeast(0L)
                    deltaSumUs += deltaUs
                    deltaCount += 1
                    if (deltaUs < minDeltaUs) minDeltaUs = deltaUs
                    if (deltaUs > maxDeltaUs) maxDeltaUs = deltaUs
                }
                lastSampleTimeUs = timeUs
                sampleCount += 1
                extractorLocal.advance()
            }
        } catch (e: Exception) {
            Log.e("VideoFrameExtractor", "Error scanning keyframes", e)
        } finally {
            extractorLocal.release()
        }
        frameCount = sampleCount
        avgFrameDurationMs = if (deltaCount > 0) {
            (deltaSumUs / deltaCount) / 1000L
        } else {
            0L
        }
        return if (keyframes.isEmpty()) longArrayOf(0L) else keyframes.toLongArray()
    }

    fun playSegment(startMs: Long, endMs: Long) {
        if (!isPlaying || decoder == null || extractor == null) return
        val clampedStartMs = startMs.coerceAtLeast(0L)
        val clampedEndMs = endMs.coerceAtLeast(clampedStartMs)
        val requestId = ++requestCounter
        latestRequestId = requestId
        handler.post {
            if (!isPlaying || decoder == null || extractor == null) return@post
            decodeSegment(clampedStartMs, clampedEndMs, requestId)
        }
    }

    private fun decodeSegment(startMs: Long, endMs: Long, requestId: Int) {
        val extractorLocal = extractor ?: return
        val decoderLocal = decoder ?: return
        try {
            decoderLocal.flush()
            extractorLocal.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val startUs = startMs * 1000L
            val endUs = endMs * 1000L
            val renderBaseNs = System.nanoTime()
            var inputEof = false
            val bufferInfo = MediaCodec.BufferInfo()

            while (isPlaying && requestId == latestRequestId) {
                if (!inputEof) {
                    val inputBufferIndex = decoderLocal.dequeueInputBuffer(10000L)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = decoderLocal.getInputBuffer(inputBufferIndex)
                        val sampleSize = extractorLocal.readSampleData(inputBuffer!!, 0)
                        if (sampleSize < 0) {
                            decoderLocal.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEof = true
                        } else {
                            val presentationTimeUs = extractorLocal.sampleTime
                            decoderLocal.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                sampleSize,
                                presentationTimeUs,
                                0
                            )
                            extractorLocal.advance()
                        }
                    }
                }

                val outputBufferIndex = decoderLocal.dequeueOutputBuffer(bufferInfo, 10000L)
                when {
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = decoderLocal.outputFormat
                        videoWidth = format.getInteger(MediaFormat.KEY_WIDTH)
                        videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
                    }
                    outputBufferIndex >= 0 -> {
                        val ptsUs = bufferInfo.presentationTimeUs
                        if (ptsUs >= startUs) {
                            val renderTimeNs = renderBaseNs + (ptsUs - startUs) * 1000L
                            decoderLocal.releaseOutputBuffer(outputBufferIndex, renderTimeNs)
                        } else {
                            decoderLocal.releaseOutputBuffer(outputBufferIndex, false)
                        }
                        if (ptsUs >= endUs ||
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            break
                        }
                    }
                    inputEof -> break
                }
            }
        } catch (e: Exception) {
            Log.e("VideoFrameExtractor", "Error decoding segment", e)
        }
    }

    fun release() {
        handler.post {
            isPlaying = false
            try {
                decoder?.stop()
                decoder?.release()
                decoder = null
            } catch (e: Exception) {}
            try {
                extractor?.release()
                extractor = null
            } catch (e: Exception) {}
            handlerThread.quitSafely()
        }
    }
}
