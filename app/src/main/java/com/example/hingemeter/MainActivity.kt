package com.example.hingemeter

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : AppCompatActivity(), SensorEventListener {
    private lateinit var angleView: AngleView
    private lateinit var sensorManager: SensorManager
    private var hingeSensor: Sensor? = null
    private var isListening = false
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
                SensorManager.SENSOR_DELAY_UI
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
        angleView.setOverrideText(null)
        angleView.setAngle(angle)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }
}
