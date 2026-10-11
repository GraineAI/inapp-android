package ai.graine.inapp

import android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
import android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
import android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
import android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
import android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
import android.media.AudioDeviceInfo.TYPE_HDMI
import android.media.AudioDeviceInfo.TYPE_HEARING_AID
import android.media.AudioDeviceInfo.TYPE_LINE_ANALOG
import android.media.AudioDeviceInfo.TYPE_USB_DEVICE
import android.media.AudioDeviceInfo.TYPE_USB_HEADSET
import android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES
import android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE PHONE'S AUDIO, AS RULES: where a call is heard (CallRouting) — the
 * loudspeaker when the customer chose it, else a Bluetooth headset, else a
 * wired one, else the earpiece, made again as devices come and go — and when
 * a microphone that died is rebuilt, or the call ends instead
 * (MicrophoneRecovery). AudioRoute and AndroidCallAudio only apply them.
 */
class CallAudioDeviceTest {
    /** Every phone: the earpiece and the loudspeaker. */
    private val phone = listOf(TYPE_BUILTIN_EARPIECE, TYPE_BUILTIN_SPEAKER)
    private val bluetooth = listOf(TYPE_BLE_HEADSET, TYPE_BLUETOOTH_SCO, TYPE_HEARING_AID)
    private val wired = listOf(TYPE_WIRED_HEADSET, TYPE_USB_HEADSET, TYPE_WIRED_HEADPHONES, TYPE_USB_DEVICE)

    private fun ear(vararg devices: Int) = CallRouting.choose(phone + devices.toList(), speaker = false)
    private fun loud(vararg devices: Int) = CallRouting.choose(phone + devices.toList(), speaker = true)

    // ── where the call is heard ─────────────────────────────────────────────

    @Test fun aPhoneAloneIsHeardAtTheEarpieceAndOnTheLoudspeakerWhenChosen() {
        assertEquals(TYPE_BUILTIN_EARPIECE, ear())
        assertEquals(TYPE_BUILTIN_SPEAKER, loud())
    }

    @Test fun aBluetoothHeadsetTakesTheCallOverAWiredOneAndTheEarpiece() {
        for (headset in bluetooth) {
            assertEquals(headset, ear(headset))
            for (plugged in wired) assertEquals("Bluetooth before what is plugged in", headset, ear(plugged, headset))
        }
    }

    @Test fun amongBluetoothHeadsetsLeAudioThenHandsFreeThenAHearingAid() {
        assertEquals(TYPE_BLE_HEADSET, ear(TYPE_HEARING_AID, TYPE_BLUETOOTH_SCO, TYPE_BLE_HEADSET))
        assertEquals(TYPE_BLUETOOTH_SCO, ear(TYPE_HEARING_AID, TYPE_BLUETOOTH_SCO))
        assertEquals(TYPE_BLE_HEADSET, ear(TYPE_HEARING_AID, TYPE_BLE_HEADSET))
    }

    @Test fun whatIsPluggedInComesBeforeTheEarpiece() {
        for (plugged in wired) assertEquals(plugged, ear(plugged))
        assertEquals(TYPE_WIRED_HEADSET, ear(TYPE_USB_DEVICE, TYPE_WIRED_HEADPHONES, TYPE_USB_HEADSET, TYPE_WIRED_HEADSET))
        assertEquals(TYPE_USB_HEADSET, ear(TYPE_USB_DEVICE, TYPE_WIRED_HEADPHONES, TYPE_USB_HEADSET))
        assertEquals(TYPE_WIRED_HEADPHONES, ear(TYPE_USB_DEVICE, TYPE_WIRED_HEADPHONES))
        assertEquals("a USB audio device that is not a headset: still before the earpiece", TYPE_USB_DEVICE, ear(TYPE_USB_DEVICE))
    }

    @Test fun theOrderTheDevicesAreListedInDecidesNothing() {
        val everything = phone + bluetooth + wired
        for (n in 0 until everything.size) {
            val rotated = everything.drop(n) + everything.take(n)
            assertEquals(TYPE_BLE_HEADSET, CallRouting.choose(rotated, speaker = false))
            assertEquals(TYPE_BUILTIN_SPEAKER, CallRouting.choose(rotated.reversed(), speaker = true))
        }
    }

    @Test fun theLoudspeakerWhenChosenWinsOverEveryHeadset() {
        assertEquals(TYPE_BUILTIN_SPEAKER, loud(*(bluetooth + wired).toIntArray()))
        for (d in bluetooth + wired) assertEquals(TYPE_BUILTIN_SPEAKER, loud(d))
    }

    /** The bug this rule exists for: loudspeaker off used to mean the earpiece, with earbuds in. */
    @Test fun turningTheLoudspeakerOffReturnsTheCallToTheHeadsetNotTheEarpiece() {
        for (headset in bluetooth + wired) {
            val devices = phone + headset
            assertEquals(headset, CallRouting.choose(devices, speaker = false))
            assertEquals(TYPE_BUILTIN_SPEAKER, CallRouting.choose(devices, speaker = true))
            assertEquals("off again: back to the headset", headset, CallRouting.choose(devices, speaker = false))
        }
    }

    @Test fun asDevicesComeAndGoMidCallTheChoiceFollows() {
        val devices = phone.toMutableList()
        assertEquals(TYPE_BUILTIN_EARPIECE, CallRouting.choose(devices, false))
        devices += TYPE_BLUETOOTH_SCO      // earbuds switched on: they take the call
        assertEquals(TYPE_BLUETOOTH_SCO, CallRouting.choose(devices, false))
        devices += TYPE_WIRED_HEADSET      // a wired headset plugged in as well: Bluetooth keeps it
        assertEquals(TYPE_BLUETOOTH_SCO, CallRouting.choose(devices, false))
        devices -= TYPE_BLUETOOTH_SCO      // earbuds off: the next in line, not the earpiece
        assertEquals(TYPE_WIRED_HEADSET, CallRouting.choose(devices, false))
        devices -= TYPE_WIRED_HEADSET      // unplugged: the earpiece
        assertEquals(TYPE_BUILTIN_EARPIECE, CallRouting.choose(devices, false))
        // On the loudspeaker a headset that arrives leaves it there — the customer chose it — until they turn it off.
        devices += TYPE_BLE_HEADSET
        assertEquals(TYPE_BUILTIN_SPEAKER, CallRouting.choose(devices, true))
        assertEquals(TYPE_BLE_HEADSET, CallRouting.choose(devices, false))
    }

    @Test fun devicesThatAreNotForACallAreNeverChosen() {
        // Music-only Bluetooth (no microphone, no call audio), a screen, a line out, a type this build has never heard of.
        val others = intArrayOf(TYPE_BLUETOOTH_A2DP, TYPE_HDMI, TYPE_LINE_ANALOG, 999)
        assertEquals(TYPE_BUILTIN_EARPIECE, ear(*others))
        assertEquals(TYPE_BUILTIN_SPEAKER, loud(*others))
        assertNull("no earpiece among them: the phone's own default", CallRouting.choose(others.toList() + TYPE_BUILTIN_SPEAKER, false))
    }

    @Test fun nothingToChooseIsThePhonesOwnDefault() {
        assertNull(CallRouting.choose(emptyList(), speaker = false))
        assertNull(CallRouting.choose(emptyList(), speaker = true))
        assertNull("a tablet has no earpiece: the platform decides", CallRouting.choose(listOf(TYPE_BUILTIN_SPEAKER), false))
        assertNull("no loudspeaker listed to choose", CallRouting.choose(listOf(TYPE_BUILTIN_EARPIECE, TYPE_BLUETOOTH_SCO), true))
    }

    // ── a microphone that died ──────────────────────────────────────────────

    @Test fun aMicrophoneThatDiesIsRebuiltOnceAndOneThatDiesAgainSoonAfterIsGivenUp() {
        val recovery = MicrophoneRecovery()
        assertTrue("the first failure: rebuild it", recovery.failed(10_000))
        assertFalse("again within 5 s: it is not coming back", recovery.failed(14_999))
    }

    @Test fun failuresFarApartAreSeparateEventsEachRebuilt() {
        val recovery = MicrophoneRecovery()
        assertEquals(5_000L, MicrophoneRecovery.WINDOW_MS)
        assertTrue(recovery.failed(0))
        assertTrue("5 s on is no longer soon", recovery.failed(5_000))
        assertTrue(recovery.failed(11_000))
        assertTrue("minutes later, the same", recovery.failed(300_000))
        assertFalse(recovery.failed(300_001))
    }

    // ── the phone taking the call's audio ───────────────────────────────────

    @Test fun losingTheAudioHoldsTheCallAndGettingItBackResumesIt() {
        assertEquals(CallFocus.Act.HOLD, CallFocus.act(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))   // a phone call ringing
        assertEquals(CallFocus.Act.HOLD, CallFocus.act(AudioManager.AUDIOFOCUS_LOSS))             // another app's call or player
        assertEquals(CallFocus.Act.RESUME, CallFocus.act(AudioManager.AUDIOFOCUS_GAIN))
        assertEquals(CallFocus.Act.RESUME, CallFocus.act(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT))
    }

    @Test fun aNotificationSoundIsNotAHold() {
        // A voice does not duck: the call goes on as it was.
        assertEquals(CallFocus.Act.NONE, CallFocus.act(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK))
        assertEquals(CallFocus.Act.NONE, CallFocus.act(0))
    }

    @Test fun onlyThePhonesOwnCallInProgressEndsTheAgentsCall() {
        assertTrue(CallFocus.phoneCallActive(AudioManager.MODE_IN_CALL))
        assertFalse(CallFocus.phoneCallActive(AudioManager.MODE_RINGTONE))          // still ringing: the hold goes on
        assertFalse(CallFocus.phoneCallActive(AudioManager.MODE_IN_COMMUNICATION))  // our own call, or another app's
        assertFalse(CallFocus.phoneCallActive(AudioManager.MODE_NORMAL))
    }
}
