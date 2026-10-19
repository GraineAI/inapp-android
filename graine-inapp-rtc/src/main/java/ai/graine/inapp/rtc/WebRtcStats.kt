package ai.graine.inapp.rtc

import ai.graine.inapp.GraineRtcInternal
import ai.graine.inapp.GraineRtcStats

/**
 * THE CALL'S AUDIO, FROM A WEBRTC STATS REPORT (the W3C stats names): what
 * arrives from the far side and how loud it is, what is sent, the round trip,
 * the codec and the path. Pure — the report's entries are handed in — so it is
 * proven on the JVM.
 */
@OptIn(GraineRtcInternal::class)
internal object WebRtcStats {
    /** One stats object: its id, its type, its members. */
    class Entry(val id: String, val type: String, val members: Map<String, Any?>)

    fun read(entries: Collection<Entry>): GraineRtcStats? {
        val byId = entries.associateBy { it.id }
        fun audio(e: Entry) = (e.members["kind"] ?: e.members["mediaType"]) == "audio"
        val inbound = entries.firstOrNull { it.type == "inbound-rtp" && audio(it) } ?: return null
        val outbound = entries.firstOrNull { it.type == "outbound-rtp" && audio(it) }
        // The pair in use: the transport's selected one, else a nominated pair that succeeded.
        val pairId = entries.firstOrNull { it.type == "transport" }?.members?.get("selectedCandidatePairId") as? String
        val pair = pairId?.let { byId[it] }
            ?: entries.firstOrNull { it.type == "candidate-pair" && it.members["nominated"] == true && it.members["state"] == "succeeded" }
        val local = (pair?.members?.get("localCandidateId") as? String)?.let { byId[it] }
        val codec = (inbound.members["codecId"] as? String)?.let { byId[it] }
        val m = inbound.members
        return GraineRtcStats(
            bytesReceived = long(m["bytesReceived"]),
            packetsReceived = long(m["packetsReceived"]),
            packetsLost = long(m["packetsLost"]),
            audioLevel = double(m["audioLevel"]),
            totalAudioEnergy = double(m["totalAudioEnergy"]),
            totalSamplesDuration = double(m["totalSamplesDuration"]),
            jitterSeconds = double(m["jitter"]),
            bytesSent = long(outbound?.members?.get("bytesSent")),
            roundTripSeconds = double(pair?.members?.get("currentRoundTripTime")),
            codec = codec?.members?.get("mimeType") as? String,
            localCandidateType = local?.members?.get("candidateType") as? String,
            clockRate = (codec?.members?.get("clockRate") as? Number)?.toInt()?.takeIf { it > 0 },
        )
    }

    private fun long(v: Any?): Long = (v as? Number)?.toLong() ?: 0L
    private fun double(v: Any?): Double? = (v as? Number)?.toDouble()?.takeIf { !it.isNaN() }
}
