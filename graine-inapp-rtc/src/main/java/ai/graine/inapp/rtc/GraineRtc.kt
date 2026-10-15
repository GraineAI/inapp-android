package ai.graine.inapp.rtc

import ai.graine.inapp.GraineRtcIceServer
import ai.graine.inapp.GraineRtcInternal
import ai.graine.inapp.GraineRtcMedia
import ai.graine.inapp.GraineRtcPeer
import android.content.Context

/**
 * WEBRTC FOR GRAINE'S NATIVE CALLS — this module's one entry point. An app
 * that adds `ai.graine:inapp-android-rtc` beside the SDK changes nothing else:
 * the SDK finds this class by its name and its calls carry their audio over
 * WebRTC; without it they go over the WebSocket, as before.
 *
 * Only the media lives here, behind the SDK's GraineRtcMedia: the signalling,
 * the session and its rules are the SDK's, so another build of WebRTC can take
 * this one's place without touching them.
 */
@OptIn(GraineRtcInternal::class)
internal class GraineRtc : GraineRtcMedia {
    override fun createPeer(context: Context, iceServers: List<GraineRtcIceServer>, listener: GraineRtcPeer.Listener): GraineRtcPeer =
        WebRtcPeer.create(context, iceServers, listener)
}
