package ch.beachscore.app.voice

import android.media.AudioDeviceInfo

/** What the "Microphone" setting distinguishes. [OTHER] is never offered, only reported. */
enum class MicKind { BUILTIN, USB, WIRED_HEADSET, OTHER }

/** An audio input as far as the app cares; built from `AudioDeviceInfo` on the device. */
data class InputDevice(val id: Int, val kind: MicKind, val productName: String)

/**
 * A stored "Microphone" choice. Type plus product name, because the device id changes every
 * time a USB receiver is plugged in again. Null everywhere means "Automatic".
 */
data class MicChoice(val kind: MicKind, val productName: String) {
    fun encode(): String = "${kind.name}|$productName"

    companion object {
        fun decode(value: String?): MicChoice? {
            if (value.isNullOrEmpty()) return null
            val bar = value.indexOf('|')
            if (bar < 0) return null
            val kind = MicKind.entries.firstOrNull { it.name == value.substring(0, bar) } ?: return null
            if (kind == MicKind.OTHER) return null
            return MicChoice(kind, value.substring(bar + 1))
        }
    }
}

/** One line of the setting: [choice] null is "Automatic". */
data class MicRow(val choice: MicChoice?, val connected: Boolean, val selected: Boolean)

/** Device list → options, stored choice → device. Pure, so it is tested on the JVM. */
object Microphones {

    fun kindOf(type: Int): MicKind = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> MicKind.BUILTIN
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> MicKind.USB
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> MicKind.WIRED_HEADSET
        // Bluetooth needs call-mode (SCO) routing, which would also take over the speaker.
        else -> MicKind.OTHER
    }

    fun choiceOf(device: InputDevice): MicChoice = MicChoice(device.kind, device.productName.trim())

    /**
     * The inputs the setting offers, one per type + product name (phones expose several
     * built-in microphones under one name): phone first, then USB, then headsets.
     */
    fun selectable(devices: List<InputDevice>): List<InputDevice> =
        devices
            .filter { it.kind != MicKind.OTHER }
            .sortedWith(compareBy({ it.kind.ordinal }, { it.id }))
            .distinctBy { choiceOf(it) }

    /** The device to prefer for [choice], or null for Android's automatic routing. */
    fun resolve(choice: MicChoice?, devices: List<InputDevice>): InputDevice? =
        choice?.let { wanted -> selectable(devices).firstOrNull { choiceOf(it) == wanted } }

    fun rows(devices: List<InputDevice>, choice: MicChoice?): List<MicRow> {
        val connected = selectable(devices).map { choiceOf(it) }
        val rows = mutableListOf(MicRow(null, connected = true, selected = choice == null))
        connected.mapTo(rows) { MicRow(it, connected = true, selected = it == choice) }
        if (choice != null && choice !in connected) rows += MicRow(choice, connected = false, selected = true)
        return rows
    }
}
