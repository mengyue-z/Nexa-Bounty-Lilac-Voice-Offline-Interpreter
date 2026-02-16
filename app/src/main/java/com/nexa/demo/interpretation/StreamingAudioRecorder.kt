package com.nexa.demo.interpretation

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.annotation.RequiresPermission

/**
 * Audio recorder optimized for streaming ASR.
 * Records audio and provides float32 samples normalized to [-1.0, 1.0].
 * 
 * @param sampleRate Sample rate in Hz (default: 16000 for ASR)
 * @param channelConfig Audio channel configuration (default: mono)
 * @param audioFormat Audio format (default: 16-bit PCM)
 * @param bufferSize Number of samples per buffer callback (default: 512)
 */
class StreamingAudioRecorder(
    private val sampleRate: Int = 16000,
    private val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
    private val bufferSize: Int = 512,
    private val enableAEC: Boolean = true,  // Enable Acoustic Echo Cancellation by default
    private val enableNS: Boolean = true    // Enable Noise Suppression by default
) {
    private var recorder: AudioRecord? = null
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    
    @Volatile
    private var isRecording = false
    private var recordingThread: Thread? = null

    companion object {
        private const val TAG = "StreamingAudioRecorder"
    }

    /**
     * Callback interface for receiving audio samples.
     */
    interface AudioCallback {
        /**
         * Called when new audio samples are available.
         * @param samples Float32 audio samples normalized to [-1.0, 1.0]
         */
        fun onAudioSamples(samples: FloatArray)
        
        /**
         * Called when recording stops.
         */
        fun onRecordingStopped()
        
        /**
         * Called when an error occurs.
         */
        fun onError(error: String)
    }

    /**
     * Start recording audio and streaming samples via callback.
     * 
     * @param callback Callback to receive audio samples
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startRecording(callback: AudioCallback) {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return
        }

        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBuffer == AudioRecord.ERROR || minBuffer == AudioRecord.ERROR_BAD_VALUE) {
            callback.onError("Failed to get minimum buffer size")
            return
        }

        // Use larger of minimum buffer or desired buffer size
        val actualBufferSize = maxOf(minBuffer, bufferSize * 2) // * 2 for 16-bit samples

        try {
            // Use VOICE_COMMUNICATION for better echo cancellation
            // Falls back to MIC if not available
            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                channelConfig,
                audioFormat,
                actualBufferSize
            )

            if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
                callback.onError("AudioRecord initialization failed")
                recorder?.release()
                recorder = null
                return
            }

            // Enable Acoustic Echo Cancellation if available and requested
            val audioSessionId = recorder?.audioSessionId ?: -1
            if (enableAEC && audioSessionId != -1) {
                if (AcousticEchoCanceler.isAvailable()) {
                    try {
                        acousticEchoCanceler = AcousticEchoCanceler.create(audioSessionId)
                        acousticEchoCanceler?.enabled = true
                        Log.d(TAG, "Acoustic Echo Cancellation enabled")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to enable AEC", e)
                    }
                } else {
                    Log.w(TAG, "Acoustic Echo Cancellation not available on this device")
                }
            }

            // Enable Noise Suppression if available and requested
            if (enableNS && audioSessionId != -1) {
                if (NoiseSuppressor.isAvailable()) {
                    try {
                        noiseSuppressor = NoiseSuppressor.create(audioSessionId)
                        noiseSuppressor?.enabled = true
                        Log.d(TAG, "Noise Suppression enabled")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to enable Noise Suppression", e)
                    }
                } else {
                    Log.w(TAG, "Noise Suppression not available on this device")
                }
            }

            recorder?.startRecording()
            isRecording = true

            recordingThread = Thread {
                streamAudio(callback)
            }.apply { 
                priority = Thread.MAX_PRIORITY
                start() 
            }

            Log.d(TAG, "Recording started: sampleRate=$sampleRate, bufferSize=$bufferSize, AEC=${acousticEchoCanceler?.enabled}, NS=${noiseSuppressor?.enabled}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            callback.onError("Failed to start recording: ${e.message}")
            stopRecording()
        }
    }

    /**
     * Stop recording audio.
     */
    fun stopRecording() {
        isRecording = false
        
        try {
            recorder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recorder", e)
        }
        
        // Release audio effects
        try {
            acousticEchoCanceler?.release()
            acousticEchoCanceler = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AEC", e)
        }
        
        try {
            noiseSuppressor?.release()
            noiseSuppressor = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing NS", e)
        }
        
        try {
            recorder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing recorder", e)
        }
        
        recorder = null
        recordingThread = null
        
        Log.d(TAG, "Recording stopped")
    }

    /**
     * Check if currently recording.
     */
    fun isRecording(): Boolean = isRecording

    /**
     * Main recording loop - reads PCM data and converts to float32 samples.
     */
    private fun streamAudio(callback: AudioCallback) {
        val pcmBuffer = ShortArray(bufferSize)
        val floatBuffer = FloatArray(bufferSize)

        while (isRecording) {
            val readResult = recorder?.read(pcmBuffer, 0, bufferSize) ?: -1

            when {
                readResult > 0 -> {
                    // Convert 16-bit PCM to float32 normalized to [-1.0, 1.0]
                    for (i in 0 until readResult) {
                        floatBuffer[i] = pcmBuffer[i].toFloat() / 32768.0f
                    }
                    
                    // If we read less than buffer size, create a smaller array
                    val samples = if (readResult == bufferSize) {
                        floatBuffer
                    } else {
                        floatBuffer.copyOf(readResult)
                    }
                    
                    callback.onAudioSamples(samples)
                }
                readResult == AudioRecord.ERROR_INVALID_OPERATION -> {
                    Log.e(TAG, "Invalid operation during read")
                    callback.onError("Recording error: invalid operation")
                    break
                }
                readResult == AudioRecord.ERROR_BAD_VALUE -> {
                    Log.e(TAG, "Bad value during read")
                    callback.onError("Recording error: bad value")
                    break
                }
                readResult == AudioRecord.ERROR_DEAD_OBJECT -> {
                    Log.e(TAG, "Dead object during read")
                    callback.onError("Recording error: dead object")
                    break
                }
            }
        }

        callback.onRecordingStopped()
    }
}
