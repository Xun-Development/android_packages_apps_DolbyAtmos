/*
 * SPDX-FileCopyrightText: 2026 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.content.SharedPreferences
import android.os.IBinder
import android.util.Log
import org.lunaris.dolby.DolbyConstants
import org.lunaris.dolby.data.DeviceStateManager
import org.lunaris.dolby.data.DolbyRepository
import org.lunaris.dolby.data.DolbyEffectUnavailableException

class DolbyEffectService : Service() {

    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val dolbyPrefs: SharedPreferences by lazy {
        getSharedPreferences("dolby_prefs", Context.MODE_PRIVATE)
    }
    private val isDeviceStateMemoryEnabled: Boolean
        get() = dolbyPrefs.getBoolean(DolbyConstants.PREF_DEVICE_STATE_MEMORY, false)
    private val handler = Handler()
    private lateinit var repository: DolbyRepository
    private lateinit var deviceStateManager: DeviceStateManager
    private var previousActiveDevice: AudioDeviceInfo? = null
    private var effectUnavailable = false
    private var callbacksRegistered = false

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            Log.d(TAG, "Devices added: ${addedDevices.map { it.productName }}")
            handleDeviceChange()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            if (effectUnavailable) return
            Log.d(TAG, "Devices removed: ${removedDevices.map { it.productName }}")
            if (isDeviceStateMemoryEnabled) {
                removedDevices.forEach { device ->
                    val key = deviceStateManager.deviceKey(device)
                    Log.d(TAG, "Snapshotting state for removed device: $key")
                    deviceStateManager.saveSnapshot(key, repository)
                }
            }
            handleDeviceChange()
        }
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            val isActive = configs?.any { it.isActive } == true
            if (isActive) {
                applyRepositoryState()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = try {
            DolbyRepository(this)
        } catch (e: DolbyEffectUnavailableException) {
            Log.w(TAG, "Dolby effect is unavailable, stopping service", e)
            effectUnavailable = true
            stopSelf()
            return
        }
        deviceStateManager = DeviceStateManager(this)
        val currentDevice = getCurrentOutputDevice()
        if (currentDevice != null) {
            previousActiveDevice = currentDevice
            if (isDeviceStateMemoryEnabled) {
                val key = deviceStateManager.deviceKey(currentDevice)
                val restored = deviceStateManager.restoreSnapshot(key, repository)
                if (!restored) applyRepositoryState()
            } else {
                applyRepositoryState()
            }
        } else {
            applyRepositoryState()
        }

        if (effectUnavailable) return
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler)
        callbacksRegistered = true
        audioManager.registerAudioPlaybackCallback(playbackCallback, handler)
        Log.d(TAG, "Dolby effect service created")
    }

    private fun handleDeviceChange() {
        if (effectUnavailable) return
        val newDevice = getCurrentOutputDevice()
        val oldDevice = previousActiveDevice

        if (oldDevice != null) {
            if (isDeviceStateMemoryEnabled) {
                val oldKey = deviceStateManager.deviceKey(oldDevice)
                Log.d(TAG, "Saving snapshot for previous device: $oldKey")
                deviceStateManager.saveSnapshot(oldKey, repository)
            }
        }

        if (newDevice != null) {
            val newKey = deviceStateManager.deviceKey(newDevice)
            if (isDeviceStateMemoryEnabled) {
                Log.d(TAG, "Restoring snapshot for new device: $newKey")
                val restored = deviceStateManager.restoreSnapshot(newKey, repository)
                if (!restored) {
                    Log.d(TAG, "First time device, applying saved state as base")
                    applyRepositoryState()
                }
            } else {
                Log.d(TAG, "Device state memory disabled, applying saved state")
                applyRepositoryState()
            }
            previousActiveDevice = newDevice
        } else {
            repository.updateSpeakerState()
            applyRepositoryState()
            previousActiveDevice = null
        }
    }

    private fun getCurrentOutputDevice(): AudioDeviceInfo? {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        val priorityOrder = listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        )

        for (type in priorityOrder) {
            val device = devices.firstOrNull { it.type == type }
            if (device != null) return device
        }
        return devices.firstOrNull()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (effectUnavailable) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        applyRepositoryState()
        return if (effectUnavailable) START_NOT_STICKY else START_STICKY
    }

    private fun applyRepositoryState() {
        if (effectUnavailable) return
        if (!repository.applySavedState()) {
            effectUnavailable = true
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::repository.isInitialized) {
            if (!effectUnavailable && ::deviceStateManager.isInitialized
                    && isDeviceStateMemoryEnabled) {
                try {
                    previousActiveDevice?.let { device ->
                        val key = deviceStateManager.deviceKey(device)
                        deviceStateManager.saveSnapshot(key, repository)
                    }
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Unable to save Dolby state during shutdown", e)
                }
            }
            repository.close()
        }
        if (callbacksRegistered) {
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
            audioManager.unregisterAudioPlaybackCallback(playbackCallback)
        }
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Dolby effect service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "DolbyEffectService"

        fun start(context: Context) {
            val intent = Intent(context, DolbyEffectService::class.java)
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, DolbyEffectService::class.java)
            context.stopService(intent)
        }
    }
}
