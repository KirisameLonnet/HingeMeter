package com.example.hingemeter

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.Choreographer
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity(), SensorEventListener {
    private lateinit var angleView: AngleView
    private lateinit var sensorManager: SensorManager
    private var hingeSensor: Sensor? = null
    private var isListening = false
    private var lastAngleDegrees = 0f

    private var isSoundEnabled = false
    private var isSoundLoopActive = false
    private var creakAudioEngine: CreakAudioEngine? = null

    private val soundFrameCallback = Choreographer.FrameCallback {
        handleSoundFrame()
    }

    private val pickGif = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            angleView.setGif(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        angleView = findViewById(R.id.angleView)
        angleView.setOnRequestGifPicker {
            pickGif.launch("image/gif")
        }
        angleView.setOnRequestSoundToggle {
            toggleSound()
        }

        sensorManager = getSystemService(SensorManager::class.java)
        hingeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        if (hingeSensor == null) {
            angleView.setOverrideText(getString(R.string.angle_unavailable))
        }

        creakAudioEngine = CreakAudioEngine(this)
    }

    override fun onResume() {
        super.onResume()
        if (hingeSensor != null && !isListening) {
            sensorManager.registerListener(this, hingeSensor, SENSOR_DELAY_US, 0)
            isListening = true
        }
        if (isSoundEnabled) {
            creakAudioEngine?.startEngine()
            ensureSoundLoop()
        }
    }

    override fun onPause() {
        super.onPause()
        if (isListening) {
            sensorManager.unregisterListener(this)
            isListening = false
        }
        stopSoundLoop()
        creakAudioEngine?.stopEngine()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSoundLoop()
        creakAudioEngine?.release()
        creakAudioEngine = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_HINGE_ANGLE) return
        val angle = event.values.firstOrNull() ?: return
        val clampedAngle = angle.coerceIn(0f, 180f)
        lastAngleDegrees = clampedAngle
        angleView.setOverrideText(null)
        angleView.setAngle(clampedAngle)
        ensureSoundLoop()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }

    private fun toggleSound() {
        if (isSoundEnabled) {
            isSoundEnabled = false
            stopSoundLoop()
            creakAudioEngine?.stopEngine()
        } else {
            isSoundEnabled = true
            creakAudioEngine?.startEngine()
            ensureSoundLoop()
        }
    }

    private fun ensureSoundLoop() {
        if (!isSoundEnabled || isSoundLoopActive) return
        isSoundLoopActive = true
        Choreographer.getInstance().postFrameCallback(soundFrameCallback)
    }

    private fun stopSoundLoop() {
        if (!isSoundLoopActive) return
        isSoundLoopActive = false
        Choreographer.getInstance().removeFrameCallback(soundFrameCallback)
    }

    private fun handleSoundFrame() {
        if (!isSoundEnabled) {
            isSoundLoopActive = false
            return
        }
        val engine = creakAudioEngine
        if (engine == null) {
            isSoundLoopActive = false
            return
        }
        engine.updateWithLidAngle(lastAngleDegrees.toDouble())
        Choreographer.getInstance().postFrameCallback(soundFrameCallback)
    }

    companion object {
        private const val SENSOR_DELAY_US = 2000
    }
}
