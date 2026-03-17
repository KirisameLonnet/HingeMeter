package com.example.hingemeter

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.min

class CreakAudioEngine(context: Context) {
    companion object {
        private const val DEADZONE = 1.0
        private const val VELOCITY_FULL = 10.0
        private const val VELOCITY_QUIET = 100.0
        private const val MIN_RATE = 0.80
        private const val MAX_RATE = 1.10
        private const val VELOCITY_SMOOTHING = 0.3
        private const val MOVEMENT_THRESHOLD = 0.5
        private const val GAIN_RAMP_MS = 50.0
        private const val RATE_RAMP_MS = 80.0
        private const val MOVEMENT_TIMEOUT_MS = 50.0
        private const val VELOCITY_DECAY = 0.5
        private const val ADDITIONAL_DECAY = 0.8
        private const val MAX_RATE_SOUNDPOOL = 2.0
        private const val MIN_RATE_SOUNDPOOL = 0.5
    }

    private val soundPool: SoundPool
    private val soundId: Int
    private var streamId = 0
    private var isSoundLoaded = false
    private var pendingStart = false

    var isEngineRunning = false
        private set
    var currentVelocity = 0.0
        private set
    var currentGain = 0.0
        private set
    var currentRate = 1.0
        private set

    private var lastLidAngle = 0.0
    private var lastUpdateTimeSec = 0.0
    private var smoothedVelocity = 0.0
    private var targetGain = 0.0
    private var targetRate = 1.0
    private var isFirstUpdate = true
    private var lastMovementTimeSec = 0.0
    private var lastRampTimeSec = 0.0

    init {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(attributes)
            .build()
        soundPool.setOnLoadCompleteListener { _, _, status ->
            if (status == 0) {
                isSoundLoaded = true
                if (pendingStart && isEngineRunning) {
                    startLoop()
                }
            }
        }
        soundId = soundPool.load(context, R.raw.creak_loop, 1)
    }

    fun startEngine() {
        if (isEngineRunning) return
        isEngineRunning = true
        if (isSoundLoaded) {
            startLoop()
        } else {
            pendingStart = true
        }
    }

    fun stopEngine() {
        if (!isEngineRunning) return
        isEngineRunning = false
        pendingStart = false
        if (streamId != 0) {
            soundPool.stop(streamId)
            streamId = 0
        }
    }

    fun release() {
        stopEngine()
        soundPool.release()
    }

    fun updateWithLidAngle(lidAngle: Double) {
        val currentTimeSec = nowSec()
        if (isFirstUpdate) {
            lastLidAngle = lidAngle
            lastUpdateTimeSec = currentTimeSec
            lastMovementTimeSec = currentTimeSec
            isFirstUpdate = false
            return
        }

        val deltaTime = currentTimeSec - lastUpdateTimeSec
        if (deltaTime <= 0.0 || deltaTime > 1.0) {
            lastUpdateTimeSec = currentTimeSec
            lastLidAngle = lidAngle
            return
        }

        val deltaAngle = lidAngle - lastLidAngle
        lastLidAngle = lidAngle
        val instantVelocity = if (abs(deltaAngle) < MOVEMENT_THRESHOLD) {
            0.0
        } else {
            abs(deltaAngle / deltaTime)
        }

        if (instantVelocity > 0.0) {
            smoothedVelocity = (VELOCITY_SMOOTHING * instantVelocity) +
                ((1.0 - VELOCITY_SMOOTHING) * smoothedVelocity)
            lastMovementTimeSec = currentTimeSec
        } else {
            smoothedVelocity *= VELOCITY_DECAY
        }

        val timeSinceMovement = currentTimeSec - lastMovementTimeSec
        if (timeSinceMovement > MOVEMENT_TIMEOUT_MS / 1000.0) {
            smoothedVelocity *= ADDITIONAL_DECAY
        }

        lastUpdateTimeSec = currentTimeSec
        currentVelocity = smoothedVelocity
        updateAudioParametersWithVelocity(smoothedVelocity)
    }

    fun setAngularVelocity(velocity: Double) {
        smoothedVelocity = velocity
        currentVelocity = velocity
        updateAudioParametersWithVelocity(velocity)
    }

    private fun updateAudioParametersWithVelocity(velocity: Double) {
        val speed = velocity
        val gain = if (speed < DEADZONE) {
            0.0
        } else {
            val e0 = maxOf(0.0, VELOCITY_FULL - 0.5)
            val e1 = VELOCITY_QUIET + 0.5
            val t = min(1.0, maxOf(0.0, (speed - e0) / (e1 - e0)))
            val s = t * t * (3.0 - 2.0 * t)
            (1.0 - s).coerceIn(0.0, 1.0)
        }

        val normalizedVelocity = (speed / VELOCITY_QUIET).coerceIn(0.0, 1.0)
        val rate = (MIN_RATE + normalizedVelocity * (MAX_RATE - MIN_RATE))
            .coerceIn(MIN_RATE, MAX_RATE)

        targetGain = gain
        targetRate = rate
        rampToTargetParameters()
    }

    private fun rampToTargetParameters() {
        if (!isEngineRunning || streamId == 0) return
        val nowSec = nowSec()
        if (lastRampTimeSec == 0.0) {
            lastRampTimeSec = nowSec
        }
        val deltaTime = nowSec - lastRampTimeSec
        lastRampTimeSec = nowSec
        if (deltaTime <= 0.0) return

        currentGain = rampValue(currentGain, targetGain, deltaTime, GAIN_RAMP_MS)
        currentRate = rampValue(currentRate, targetRate, deltaTime, RATE_RAMP_MS)

        val volume = (currentGain * 2.0).coerceIn(0.0, 1.0).toFloat()
        soundPool.setVolume(streamId, volume, volume)
        val rate = currentRate.coerceIn(MIN_RATE_SOUNDPOOL, MAX_RATE_SOUNDPOOL).toFloat()
        soundPool.setRate(streamId, rate)
    }

    private fun rampValue(current: Double, target: Double, dtSec: Double, tauMs: Double): Double {
        val alpha = min(1.0, dtSec / (tauMs / 1000.0))
        return current + (target - current) * alpha
    }

    private fun startLoop() {
        pendingStart = false
        if (streamId != 0) {
            soundPool.stop(streamId)
        }
        streamId = soundPool.play(soundId, 0f, 0f, 1, -1, 1f)
        if (streamId == 0) {
            isEngineRunning = false
        }
    }

    private fun nowSec(): Double {
        return SystemClock.elapsedRealtimeNanos() / 1_000_000_000.0
    }
}
