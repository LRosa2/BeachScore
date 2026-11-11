package ch.beachscore.app.voice

import android.media.AudioDeviceInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MicrophonesTest {

    private val phoneBottom = InputDevice(id = 7, kind = MicKind.BUILTIN, productName = "moto g15 power")
    private val phoneBack = InputDevice(id = 9, kind = MicKind.BUILTIN, productName = "moto g15 power")
    private val receiver = InputDevice(id = 31, kind = MicKind.USB, productName = "Wireless Mic Rx")
    private val headset = InputDevice(id = 12, kind = MicKind.WIRED_HEADSET, productName = "")
    private val bluetooth = InputDevice(id = 40, kind = MicKind.OTHER, productName = "JBL Flip")

    @Test
    fun `device types map to the kinds the setting offers, bluetooth and the rest are other`() {
        assertEquals(MicKind.BUILTIN, Microphones.kindOf(AudioDeviceInfo.TYPE_BUILTIN_MIC))
        assertEquals(MicKind.USB, Microphones.kindOf(AudioDeviceInfo.TYPE_USB_DEVICE))
        assertEquals(MicKind.USB, Microphones.kindOf(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals(MicKind.USB, Microphones.kindOf(AudioDeviceInfo.TYPE_USB_ACCESSORY))
        assertEquals(MicKind.WIRED_HEADSET, Microphones.kindOf(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals(MicKind.OTHER, Microphones.kindOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertEquals(MicKind.OTHER, Microphones.kindOf(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertEquals(MicKind.OTHER, Microphones.kindOf(AudioDeviceInfo.TYPE_TELEPHONY))
        assertEquals(MicKind.OTHER, Microphones.kindOf(AudioDeviceInfo.TYPE_REMOTE_SUBMIX))
        assertEquals(MicKind.OTHER, Microphones.kindOf(AudioDeviceInfo.TYPE_FM_TUNER))
    }

    @Test
    fun `selectable list has one phone entry, then usb, then headset, and no bluetooth`() {
        val list = Microphones.selectable(listOf(receiver, bluetooth, phoneBack, headset, phoneBottom))
        assertEquals(listOf(phoneBottom, receiver, headset), list)
    }

    @Test
    fun `product names are trimmed, so a padded name is the same device`() {
        val padded = receiver.copy(id = 50, productName = "  Wireless Mic Rx ")
        assertEquals(MicChoice(MicKind.USB, "Wireless Mic Rx"), Microphones.choiceOf(padded))
        assertEquals(listOf(padded), Microphones.selectable(listOf(padded)))
    }

    @Test
    fun `choice survives storage, also with a separator in the product name`() {
        val odd = MicChoice(MicKind.USB, "Rx | 2.4 GHz")
        assertEquals(odd, MicChoice.decode(odd.encode()))
        assertEquals(MicChoice(MicKind.BUILTIN, ""), MicChoice.decode(MicChoice(MicKind.BUILTIN, "").encode()))
        assertNull(MicChoice.decode(null), "never stored: automatic")
        assertNull(MicChoice.decode(""), "automatic")
        assertNull(MicChoice.decode("TOASTER|x"), "unknown kind from a future or broken file")
        assertNull(MicChoice.decode("no separator"))
    }

    @Test
    fun `stored choice finds the receiver again after it was replugged with a new id`() {
        val choice = Microphones.choiceOf(receiver)
        val replugged = receiver.copy(id = 77)
        assertEquals(replugged, Microphones.resolve(choice, listOf(phoneBottom, replugged)))
    }

    @Test
    fun `automatic or a missing device resolves to no preferred device`() {
        assertNull(Microphones.resolve(null, listOf(phoneBottom, receiver)))
        val choice = Microphones.choiceOf(receiver)
        assertNull(Microphones.resolve(choice, listOf(phoneBottom, phoneBack)), "unplugged: fall back to automatic")
        assertNull(Microphones.resolve(MicChoice(MicKind.USB, "Other Rx"), listOf(receiver)), "a different usb device is not it")
        assertNull(Microphones.resolve(MicChoice(MicKind.WIRED_HEADSET, "Wireless Mic Rx"), listOf(receiver)), "same name, other type")
    }

    @Test
    fun `a phone choice resolves to the first built-in microphone`() {
        assertEquals(phoneBottom, Microphones.resolve(Microphones.choiceOf(phoneBack), listOf(phoneBack, receiver, phoneBottom)))
    }

    @Test
    fun `rows start with automatic and mark the selection`() {
        val rows = Microphones.rows(listOf(receiver, phoneBottom, phoneBack), Microphones.choiceOf(receiver))
        assertEquals(
            listOf(
                MicRow(choice = null, connected = true, selected = false),
                MicRow(MicChoice(MicKind.BUILTIN, "moto g15 power"), connected = true, selected = false),
                MicRow(MicChoice(MicKind.USB, "Wireless Mic Rx"), connected = true, selected = true),
            ),
            rows,
        )
        assertEquals(true, Microphones.rows(listOf(phoneBottom), null).first().selected)
    }

    @Test
    fun `a stored choice that is not plugged in stays visible as not connected`() {
        val rows = Microphones.rows(listOf(phoneBottom), MicChoice(MicKind.USB, "Wireless Mic Rx"))
        assertEquals(MicRow(MicChoice(MicKind.USB, "Wireless Mic Rx"), connected = false, selected = true), rows.last())
        assertEquals(3, rows.size)
    }
}
