package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.MediaCodecList
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AudioOutputInfo(
    val routeName: String = "Altavoz del Dispositivo",
    val isUsbDacConnected: Boolean = false,
    val isBluetooth: Boolean = false,
    val isWiredHeadset: Boolean = false,
    val maxSupportedSampleRateHz: Int = 48_000,
    val bitPerfectSupported: Boolean = false,
    val bitPerfectActive: Boolean = false
)

class AudioOutputMonitor(private val context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _outputInfo = MutableStateFlow(AudioOutputInfo())
    val outputInfo: StateFlow<AudioOutputInfo> = _outputInfo.asStateFlow()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh()
    }

    init {
        refresh()
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
    }

    fun refresh(bitPerfectRequested: Boolean = false) {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val usbDac = devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
        }
        val wired = devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
        }
        val bluetooth = devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }

        val activeDevice = usbDac ?: wired ?: bluetooth ?: devices.firstOrNull()
        val maxRate = activeDevice?.sampleRates?.maxOrNull()?.takeIf { it > 0 } ?: 48_000

        var bitPerfectAvailable = false
        var bitPerfectNow = false
        if (Build.VERSION.SDK_INT >= 34 && usbDac != null) {
            val supported = runCatching { audioManager.getSupportedMixerAttributes(usbDac) }.getOrNull().orEmpty()
            bitPerfectAvailable = supported.any {
                it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
            }
            if (bitPerfectAvailable && bitPerfectRequested) {
                val targetAttr = supported.firstOrNull {
                    it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
                }
                if (targetAttr != null) {
                    val mediaAttr = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                    bitPerfectNow = runCatching {
                        audioManager.setPreferredMixerAttributes(mediaAttr, usbDac, targetAttr)
                    }.getOrDefault(false)
                }
            }
        }

        val routeLabel = when {
            usbDac != null -> usbDac.productName?.toString()?.takeIf { it.isNotBlank() } ?: "DAC USB Hi-Res"
            wired != null -> "Auriculares Cableados"
            bluetooth != null -> bluetooth.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Audio Bluetooth"
            else -> "Altavoz Hi-Res"
        }

        _outputInfo.value = AudioOutputInfo(
            routeName = routeLabel,
            isUsbDacConnected = usbDac != null,
            isBluetooth = bluetooth != null && wired == null && usbDac == null,
            isWiredHeadset = wired != null && usbDac == null,
            maxSupportedSampleRateHz = maxRate,
            bitPerfectSupported = bitPerfectAvailable,
            bitPerfectActive = bitPerfectNow
        )
    }

    fun release() {
        runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
    }

    companion object {
        fun hasNativeAlacDecoder(): Boolean {
            return runCatching {
                val codecs = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                codecs.any { info ->
                    !info.isEncoder && info.supportedTypes.any { it.equals("audio/alac", ignoreCase = true) }
                }
            }.getOrDefault(false)
        }
    }
}
