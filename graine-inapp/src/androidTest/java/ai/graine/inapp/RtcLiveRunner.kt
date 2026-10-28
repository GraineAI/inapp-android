@file:OptIn(GraineRtcInternal::class)

package ai.graine.inapp

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import java.util.Collections
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A REAL CALL ON WEBRTC, ON A DEVICE — opt-in, by hand, never in a normal run.
 * Each scenario is a real call on the workspace the key belongs to: keep them
 * few and short.
 *
 *   ./gradlew :graine-inapp:assembleDebugAndroidTest
 *   adb install -r -t graine-inapp/build/outputs/apk/androidTest/debug/graine-inapp-debug-androidTest.apk
 *   adb shell pm grant ai.graine.inapp.test android.permission.RECORD_AUDIO
 *   adb shell am instrument -w -e GRAINE_LIVE_RTC 1 -e key <publishable key> -e scenario call \
 *       ai.graine.inapp.test/ai.graine.inapp.RtcLiveRunner
 *
 * `call`: the WebRTC session as a call places it — mint, REGISTER, INVITE,
 * 200, ICE connected, the agent's audio arriving and its level, the control
 * channel joined, a clean BYE — with each step's time. `fallback`: the same
 * call with its mint refused on the device, so it continues on the WebSocket
 * inside the same call, and the agent's audio arrives there. `app`: the call
 * as an app places it — GraineInApp.init, calls.startCall from an activity in
 * front, the call's foreground service, the call screen — on WebRTC, then
 * calls.end(). `answer` (no network, no call): the server's own answer set on
 * a fresh peer. `-e base` points at another server; the default is Graine's.
 *
 * `ring`: a call the agent places to the user, as an app receives and answers
 * it — calls.handlePush with an `agent_call` push (what the app's messaging
 * service hands over), the ring, calls.answer() from an activity in front after
 * `-e answerAfterMs` (3000), then the call held `-e holdMs` (9000). With
 * `-e dropAtMs N` the network goes away N ms after the call is active
 * (airplane mode, for `-e dropForMs`, default 5000) and comes back. The
 * weak-network lab (scripts/lab/booked-call-lab.mjs) shapes the emulator's
 * link around it; every step the SDK logs is on this run's clock.
 */
class RtcLiveRunner : Instrumentation() {
    private var arguments: Bundle = Bundle()
    private val report = StringBuilder()
    private val began = SystemClock.elapsedRealtime()

    override fun onCreate(arguments: Bundle?) {
        this.arguments = arguments ?: Bundle()
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        super.onStart()
        val ok = try {
            if (arguments.getString("scenario") == "answer") {
                // No network, no call: the media alone, against the SBC's own answer.
                answer()
            } else if (arguments.getString("GRAINE_LIVE_RTC") != "1") {
                line("skipped: pass -e GRAINE_LIVE_RTC 1 (each run is a real call)")
                true
            } else {
                val key = arguments.getString("key") ?: error("pass -e key <publishable key>")
                val base = arguments.getString("base") ?: "https://www.graine.ai"
                when (val scenario = arguments.getString("scenario") ?: "call") {
                    "call" -> call(base, key)
                    "fallback" -> fallback(base, key)
                    "answer" -> answer()
                    "app" -> app(base, key)
                    "ring" -> ring(base, key)
                    else -> error("no scenario \"$scenario\" (call, fallback, answer, app, ring)")
                }
            }
        } catch (t: Throwable) {
            line("ERROR ${t.javaClass.simpleName}: ${t.message}")
            false
        }
        line(if (ok) "RESULT: PASS" else "RESULT: FAIL")
        val out = Bundle()
        out.putString(REPORT_KEY_STREAMRESULT, "\n" + report.toString())
        finish(if (ok) Activity.RESULT_OK else Activity.RESULT_CANCELED, out)
    }

    private fun t(): Long = SystemClock.elapsedRealtime() - began

    @Synchronized
    private fun line(s: String) {
        val l = String.format("%6d ms  %s", t(), s)
        report.append(l).append('\n')
        Log.i("GraineRtcLive", l)
    }

    private fun check(what: String, ok: Boolean, detail: String = ""): Boolean {
        line("${if (ok) "ok  " else "FAIL"} $what${if (detail.isNotEmpty()) " — $detail" else ""}")
        return ok
    }

    /** One SIP message as a line: its first line and CSeq, never its credentials or addresses. */
    private fun sipLine(direction: Char, text: String) {
        val first = text.substringBefore("\r\n")
        val cseq = Regex("(?m)^CSeq: *(.+)$").find(text)?.groupValues?.get(1)?.trim().orEmpty()
        val extra = buildList {
            if (text.contains("\r\nAuthorization:")) add("+Authorization")
            if (text.contains("\r\nProxy-Authorization:")) add("+Proxy-Authorization")
            Regex("(?m)^X-Call-Sid: *(.+)$").find(text)?.let { add("X-Call-Sid ${it.groupValues[1].trim().take(8)}…") }
        }
        val shown = if (first.startsWith("SIP/2.0")) first else first.substringBefore(" sip:") + " " + first.substringAfter(" ").substringBefore("@").take(14) + "…"
        line("sip ${if (direction == '>') "→" else "←"} $shown  [$cseq]${if (extra.isNotEmpty()) " " + extra.joinToString(" ") else ""}")
    }

    private fun agentSession(base: String, key: String): SessionConfig {
        val s = SessionFetch.fetch(base, key)
        line("session: agent ${s.agentId.take(8)}…, web-call agent ${s.webcallAgentId.take(8)}…, sockets ${s.wsHost ?: "default"}")
        return s
    }

    private fun identity() = IdentityEnvelope(value = null, installId = "rtc-live-" + UUID.randomUUID().toString().take(8), memory = false, timezone = TimeZone.getDefault().id)

    // ── call: the WebRTC session against the real server ──

    private fun call(base: String, key: String): Boolean {
        val session = agentSession(base, key)
        val media = RtcModule.media() ?: error("the WebRTC module is not in this build")
        val scheduler = ExecutorScheduler("graine-rtc-live")
        val sipCodes = Collections.synchronizedList(ArrayList<String>())
        var byeAnswered = false
        val env = RtcEnv(
            scheduler = scheduler,
            createPeer = { servers, l ->
                line("ice servers: ${servers.joinToString { s -> s.urls.joinToString("|") { it.substringBefore(':') } }}")
                media.createPeer(context, servers, l)
            },
            steps = { step, detail -> line("step $step: $detail") },
            sipTrace = { direction, text ->
                sipLine(direction, text)
                if (direction == '<' && text.startsWith("SIP/2.0")) {
                    val code = text.substring(8, 11)
                    val method = Regex("(?m)^CSeq: *\\d+ +(\\S+)").find(text)?.groupValues?.get(1)
                    sipCodes += "$code $method"
                    if (method == "BYE" && code == "200") byeAnswered = true
                }
            },
        )
        val callId = "call_rtclive_" + UUID.randomUUID().toString().take(12)
        val request = RtcCallRequest(
            baseUrl = base,
            publishableKey = key,
            variables = RtcMint.variables(mapOf("who_called" to "customer", "call_reason" to "webrtc_live_test"), emptyMap()),
            identity = identity(),
            callId = callId,
            wsHost = session.wsHost,
        )
        val rtc = RtcCallSession(request, env)
        val media0 = CountDownLatch(1)
        val voice = CountDownLatch(1)
        val joined = CountDownLatch(1)
        val ended = CountDownLatch(1)
        var outcome = "none"
        var mediaAt = 0L
        var controlAt = 0L
        var lastStats: GraineRtcStats? = null
        var maxLevel = 0.0
        var firstVoiceAt = 0L
        val captions = Collections.synchronizedList(ArrayList<String>())
        val listener = object : RtcCallSession.Listener {
            override fun onMedia() { mediaAt = t(); media0.countDown() }
            override fun onFallback(reason: String, detail: String) { outcome = "fallback $reason ($detail)"; ended.countDown(); media0.countDown() }
            override fun onRefused(message: String) { outcome = "refused $message"; ended.countDown(); media0.countDown() }
            override fun onEnded(reason: GraineCallOutcome.EndReason, error: String?) { outcome = "ended ${reason.wire} ${error.orEmpty()}"; ended.countDown() }
            override fun onCaption(role: Role, text: String, live: Boolean) { captions += "${role.name.lowercase()}${if (live) "…" else ""}: ${text.take(60)}" }
            override fun onControlJoined() { controlAt = t(); joined.countDown() }
            override fun onAudioStats(stats: GraineRtcStats) {
                lastStats = stats
                val level = stats.audioLevel ?: 0.0
                if (level > maxLevel) maxLevel = level
                if (level > 0.01 && firstVoiceAt == 0L) { firstVoiceAt = t(); voice.countDown() }
            }
        }
        val start = t()
        rtc.prepare()
        // The tap prepared it; the foreground service comes up a moment later: dial.
        Thread.sleep(300)
        line("dial")
        rtc.dial(RtcDialDeps({ _, _, answer -> answer(ActionResult.refused("the live test runs no actions")) }, { null }, session.wsHost), listener)
        media0.await(14, TimeUnit.SECONDS)
        if (mediaAt == 0L) {
            line("no media: $outcome")
            rtc.hangUp()
            Thread.sleep(2_500)
            return false
        }
        voice.await(6, TimeUnit.SECONDS)
        joined.await(3, TimeUnit.SECONDS)
        // A little of the greeting, then hang up.
        Thread.sleep(1_500)
        val s = lastStats
        val hangAt = t()
        rtc.hangUp()
        Thread.sleep(2_500)
        line("captions: ${captions.joinToString(" | ").ifEmpty { "none" }}")
        line("sip codes: ${sipCodes.joinToString(", ")}")
        var ok = true
        ok = check("the INVITE was answered 200", sipCodes.contains("200 INVITE"), sipCodes.filter { it.endsWith("INVITE") }.joinToString(" → ")) && ok
        ok = check("ICE connected", mediaAt > 0, "media at ${mediaAt - start} ms from the start") && ok
        ok = check("the agent's audio arrived", (s?.bytesReceived ?: 0) > 0,
            "${s?.bytesReceived} bytes, ${s?.packetsReceived} packets, lost ${s?.packetsLost}, codec ${s?.codec}, path ${s?.localCandidateType}, rtt ${s?.roundTripSeconds}") && ok
        ok = check("the agent spoke (level > 0.01)", firstVoiceAt > 0, "max level ${"%.3f".format(maxLevel)}, first voice ${if (firstVoiceAt > 0) "${firstVoiceAt - mediaAt} ms after media" else "never"}") && ok
        ok = check("the control channel joined", controlAt > 0, if (controlAt > 0) "${controlAt - mediaAt} ms after media" else "never") && ok
        ok = check("a clean BYE (200)", byeAnswered) && ok
        ok = check("under 20 s", hangAt - start < 20_000, "${hangAt - start} ms to the hang-up") && ok
        return ok
    }

    // ── app: the call as an app places it ──

    private fun app(base: String, key: String): Boolean {
        val application = context.applicationContext as android.app.Application
        lateinit var graine: GraineInApp
        runOnMainSync {
            graine = GraineInApp.init(application, GraineConfig(baseUrl = base, publishableKey = key, debug = true))
            graine.calls.configure(GraineCallsConfig(displayName = "Live test", callTransport = GraineCallTransport.WEBRTC))
        }
        val loaded = SystemClock.elapsedRealtime() + 10_000
        while (graine.session == null && SystemClock.elapsedRealtime() < loaded) Thread.sleep(100)
        line("session ${if (graine.session != null) "loaded" else "not loaded"}")
        val states = Collections.synchronizedList(ArrayList<String>())
        val captions = Collections.synchronizedList(ArrayList<String>())
        val active = CountDownLatch(1)
        val over = CountDownLatch(1)
        var outcome: GraineCallOutcome? = null
        var activeAt = 0L
        fun record(o: GraineCallOutcome) {
            line("outcome ${o.wire} ${o.reason.orEmpty()}")
            if (o.kind != GraineCallOutcome.Kind.ANSWERED) outcome = o
        }
        val listener = object : GraineCallListener {
            override fun onCallState(state: GraineCallState, call: GraineCall?) {
                states += state.name.lowercase()
                if (state == GraineCallState.ACTIVE) { activeAt = t(); active.countDown() }
                if (state == GraineCallState.IDLE) over.countDown()
            }
            override fun onCallCaption(role: Role, text: String, live: Boolean) { captions += "${role.name.lowercase()}${if (live) "…" else ""}: ${text.take(60)}" }
            override fun onCallOutcome(callId: String, outcome: GraineCallOutcome) = record(outcome)
        }
        runOnMainSync { graine.calls.addListener(listener) }
        val activity = startActivitySync(android.content.Intent(context, RtcLiveActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val start = t()
        var callId: String? = null
        runOnMainSync { callId = graine.calls.startCall(activity, reason = "webrtc_live_app") }
        line("startCall → ${callId?.take(13)}…; transport planned: ${graine.calls.transport}")
        active.await(15, TimeUnit.SECONDS)
        val transport = graine.calls.transport
        val why = graine.calls.transportFallbackReason
        line("active ${if (activeAt > 0) "${activeAt - start} ms after startCall" else "never"}; transport $transport${why?.let { " ($it)" } ?: ""}")
        if (activeAt > 0) Thread.sleep(3_000)
        val hangAt = t()
        runOnMainSync { graine.calls.end() }
        over.await(5, TimeUnit.SECONDS)
        Thread.sleep(2_500)
        runCatching { activity.finish() }
        line("states: ${states.joinToString(" → ")}")
        line("captions: ${captions.takeLast(4).joinToString(" | ").ifEmpty { "none" }}")
        var ok = true
        ok = check("the call was answered and connected", activeAt > 0) && ok
        ok = check("WebRTC carried it", transport == CallTransportChoice.WEBRTC, "transport $transport, reason $why") && ok
        ok = check("ended by this side", outcome?.kind == GraineCallOutcome.Kind.ENDED && outcome?.endReason == GraineCallOutcome.EndReason.LOCAL, "${outcome?.wire} ${outcome?.reason}") && ok
        ok = check("captions reached the app", captions.isNotEmpty(), captions.size.toString()) && ok
        ok = check("under 20 s", hangAt - start < 20_000, "${hangAt - start} ms to the hang-up") && ok
        return ok
    }

    // ── ring: a call the agent places to the user, received and answered as an app does ──

    private fun ring(base: String, key: String): Boolean {
        val answerAfter = arguments.getString("answerAfterMs")?.toLongOrNull() ?: 3_000L
        val hold = arguments.getString("holdMs")?.toLongOrNull() ?: 9_000L
        val dropAt = arguments.getString("dropAtMs")?.toLongOrNull()
        val dropFor = arguments.getString("dropForMs")?.toLongOrNull() ?: 5_000L
        val application = context.applicationContext as android.app.Application
        lateinit var graine: GraineInApp
        runOnMainSync {
            graine = GraineInApp.init(application, GraineConfig(baseUrl = base, publishableKey = key, debug = true))
            graine.calls.configure(GraineCallsConfig(displayName = "Live test", callTransport = GraineCallTransport.WEBRTC))
        }
        // The SDK's own steps — the mint, the registration, the INVITE, ICE, the agent's voice — on this run's clock.
        CallLog.enabled = true
        val logcat = CallLog.sink
        CallLog.sink = { m, e -> line("sdk: ${m.take(220)}"); logcat?.invoke(m, e) }
        val loaded = SystemClock.elapsedRealtime() + 10_000
        while (graine.session == null && SystemClock.elapsedRealtime() < loaded) Thread.sleep(100)
        line("session ${if (graine.session != null) "loaded" else "not loaded"}")

        val ringing = CountDownLatch(1)
        val active = CountDownLatch(1)
        val over = CountDownLatch(1)
        var activeAt = 0L
        var heardAt = 0L
        var dropEndAt = 0L
        var heardAfterDropAt = 0L
        var endedEarlyAt = 0L
        var outcome: GraineCallOutcome? = null
        val recovering = Collections.synchronizedList(ArrayList<String>())
        val captions = Collections.synchronizedList(ArrayList<String>())
        val listener = object : GraineCallListener {
            override fun onIncomingCall(call: GraineCall) { line("ringing (${call.direction.name.lowercase()})"); ringing.countDown() }
            override fun onCallState(state: GraineCallState, call: GraineCall?) {
                line("state ${state.name.lowercase()}")
                if (state == GraineCallState.ACTIVE && activeAt == 0L) { activeAt = t(); active.countDown() }
                if (state == GraineCallState.ENDED && endedEarlyAt == 0L) endedEarlyAt = t()
                if (state == GraineCallState.IDLE) over.countDown()
            }
            override fun onCallCaption(role: Role, text: String, live: Boolean) {
                if (!live && text.isNotBlank()) { captions += "${role.name.lowercase()}: ${text.take(60)}"; line("caption ${role.name.lowercase()}: ${text.take(70)}") }
            }
            // The agent heard on the device — the level the call screen draws, on either transport.
            override fun onAgentLevel(level: Float) {
                if (level <= 0.01f) return
                if (heardAt == 0L) { heardAt = t(); line("agent heard (level ${"%.2f".format(level)})") }
                if (dropEndAt > 0 && heardAfterDropAt == 0L && t() > dropEndAt) { heardAfterDropAt = t(); line("agent heard again after the drop") }
            }
            override fun onCallReconnecting(on: Boolean) { recovering += "${if (on) "on" else "off"}@${t()}"; line("reconnecting ${if (on) "on" else "off"}") }
            override fun onCallOutcome(callId: String, o: GraineCallOutcome) {
                line("outcome ${o.wire} ${o.reason.orEmpty()}")
                if (o.kind != GraineCallOutcome.Kind.ANSWERED) outcome = o
            }
        }
        runOnMainSync { graine.calls.addListener(listener) }
        // The app in front, as when the user answers from the app's own ring screen.
        val activity = startActivitySync(android.content.Intent(context, RtcLiveActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val callId = "call_netlab_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val pushAt = t()
        val taken = graine.calls.handlePush(mapOf("type" to "agent_call", "call_id" to callId, "reason" to "weak_network_lab", "caller_name" to "Weak-network lab"))
        line("push handed over: ${if (taken) "a Graine call" else "not ours"}")
        ringing.await(5, TimeUnit.SECONDS)
        // The user looks at the ring; meanwhile the ring's own WebRTC leg is prepared (mint, registration).
        Thread.sleep(answerAfter)
        val answerAt = t()
        line("answer (${answerAt - pushAt} ms after the push)")
        runOnMainSync { graine.calls.answer() }
        active.await(25, TimeUnit.SECONDS)
        val transport = graine.calls.transport
        val why = graine.calls.transportFallbackReason
        line("active ${if (activeAt > 0) "${activeAt - answerAt} ms after the answer" else "never"}; transport $transport${why?.let { " ($it)" } ?: ""}")

        var dropStartAt = 0L
        if (activeAt > 0 && dropAt != null) {
            Thread.sleep(dropAt)
            dropStartAt = t()
            line("drop: the network goes away for $dropFor ms (airplane mode)")
            shell("cmd connectivity airplane-mode enable")
            Thread.sleep(dropFor)
            shell("cmd connectivity airplane-mode disable")
            dropEndAt = t()
            line("drop: the network is back")
        }
        if (activeAt > 0) {
            val until = maxOf(activeAt, dropEndAt) + hold
            while (t() < until && over.count > 0) Thread.sleep(100)
        }
        val hangAt = t()
        val endedBeforeHangUp = endedEarlyAt in 1 until hangAt
        runOnMainSync { graine.calls.end() }
        over.await(6, TimeUnit.SECONDS)
        Thread.sleep(2_000)
        runCatching { activity.finish() }
        if (dropStartAt > 0) runCatching { shell("cmd connectivity airplane-mode disable") }

        val ms = { at: Long -> if (at > 0) "${at - answerAt} ms" else "never" }
        line("SUMMARY answer→active ${ms(activeAt)}; answer→agent heard ${ms(heardAt)}; transport $transport${why?.let { " ($it)" } ?: ""}; " +
            "reconnecting ${recovering.joinToString().ifEmpty { "never" }}; heard after the drop ${if (heardAfterDropAt > 0) "${heardAfterDropAt - dropEndAt} ms after it" else "no"}; " +
            "ended before the hang-up ${if (endedBeforeHangUp) "yes, ${endedEarlyAt - answerAt} ms after the answer" else "no"}; outcome ${outcome?.wire} ${outcome?.reason.orEmpty()}")
        line("captions: ${captions.take(3).joinToString(" | ").ifEmpty { "none" }}")
        var ok = true
        ok = check("the ring rang", taken && ringing.count == 0L) && ok
        ok = check("the answered call connected", activeAt > 0) && ok
        ok = check("the agent was heard", heardAt > 0, ms(heardAt)) && ok
        if (dropStartAt == 0L) ok = check("ended by this side", !endedBeforeHangUp, outcome?.wire.orEmpty()) && ok
        return ok
    }

    /** A shell command as the shell user (airplane mode for the drop), waited for. */
    private fun shell(cmd: String): String {
        val pfd = uiAutomation.executeShellCommand(cmd)
        return try { java.io.FileInputStream(pfd.fileDescriptor).bufferedReader().use { it.readText() } } finally { runCatching { pfd.close() } }
    }

    // ── answer: the SBC's answer applied to a fresh peer, offline ──

    /**
     * The production SBC's answer (rtc-shared/TRACE.md §3, its addresses and
     * keys standing in) set on a peer made exactly as a call makes it — no
     * server, no call. What a call does with its 200 OK, with nothing else
     * that could fail around it.
     */
    private fun answer(): Boolean {
        val media = RtcModule.media() ?: error("the WebRTC module is not in this build")
        val events = object : GraineRtcPeer.Listener {
            override fun onCandidate(candidate: String) {}
            override fun onGatheringComplete() {}
            override fun onIceState(state: String) { line("ice $state") }
            override fun onAudioError(message: String) { line("audio error $message") }
        }
        val peer = media.createPeer(context, emptyList(), events)
        val offered = CountDownLatch(1)
        var offerError: String? = null
        peer.createOffer(false) { e -> offerError = e; offered.countDown() }
        offered.await(5, TimeUnit.SECONDS)
        val offer = peer.localDescription().orEmpty()
        line("offer: ${offerError ?: "ok"}; m=${offer.lines().firstOrNull { it.startsWith("m=audio") }}")
        line("offer has PCMU at 0: ${offer.contains("a=rtpmap:0 PCMU/8000")}; telephone-event/8000: ${Regex("a=rtpmap:(\\d+) telephone-event/8000").find(offer)?.groupValues?.get(1)}")
        line("offer bundle: ${offer.lines().firstOrNull { it.startsWith("a=group:") }}; setup: ${offer.lines().firstOrNull { it.startsWith("a=setup:") }}")
        val fingerprint = (0 until 32).joinToString(":") { "%02X".format((it * 37 + 11) and 0xff) }
        val answer = listOf(
            "v=0", "o=Jambonz-Mediaserver 1791980511 1791980512 IN IP4 203.0.113.50", "s=Jambonz-Mediaserver", "t=0 0",
            "m=audio 40414 UDP/TLS/RTP/SAVPF 0 126", "c=IN IP4 203.0.113.50", "a=mid:0", "a=rtpmap:0 PCMU/8000",
            "a=rtpmap:126 telephone-event/8000", "a=fmtp:126 0-15", "a=sendrecv", "a=rtcp:40414", "a=rtcp-mux", "a=setup:active",
            "a=fingerprint:sha-256 $fingerprint", "a=tls-id:971c16c527f442e59dc2001a9d8e8959", "a=ptime:20",
            "a=ice-ufrag:DLtsxRNa", "a=ice-pwd:abcdefghijklmnopqrstuvwx", "a=ice-options:trickle",
            "a=candidate:IEr81xVVpfGHdWH2 1 UDP 2130706431 203.0.113.50 40414 typ host", "a=end-of-candidates", "",
        ).joinToString("\r\n")
        val set = CountDownLatch(1)
        var setError: String? = null
        peer.setRemoteAnswer(answer) { e -> setError = e; set.countDown() }
        set.await(5, TimeUnit.SECONDS)
        val ok = check("the SBC's answer is accepted", setError == null, setError ?: "")
        peer.close()
        return ok
    }

    // ── fallback: a refused mint, the same call on the WebSocket ──

    private class CountingAudio : CallAudioIO {
        @Volatile var samples = 0L
        @Volatile var peak = 0
        @Volatile private var running = false
        override fun startCapture(onFrame: (ShortArray) -> Unit, onFailed: () -> Unit) {
            // A silent microphone, as the emulator's is: 20 ms of silence every 20 ms.
            running = true
            Thread({ while (running) { onFrame(ShortArray(AgentAudio.CAPTURE_FRAME_SAMPLES)); Thread.sleep(20) } }, "live-mic").apply { isDaemon = true }.start()
        }
        override fun play(pcm: ShortArray, sampleRate: Int) {
            samples += pcm.size
            for (v in pcm) if (Math.abs(v.toInt()) > peak) peak = Math.abs(v.toInt())
        }
        override fun clear() {}
        override fun bufferedSeconds(): Double = 0.0
        override fun stop() { running = false }
    }

    private fun fallback(base: String, key: String): Boolean {
        val session = agentSession(base, key)
        val media = RtcModule.media() ?: error("the WebRTC module is not in this build")
        val scheduler = ExecutorScheduler("graine-rtc-live")
        // The mint refused on the device (nothing is sent for it); everything else is the real server.
        val http = HttpPost { url, json ->
            if (url.endsWith(RtcMint.PATH)) HttpReply(503, "{\"error\":\"refused by the live test\"}") else PlatformHttp.post(url, json)
        }
        val callId = "call_rtclive_" + UUID.randomUUID().toString().take(12)
        val said = Collections.synchronizedList(ArrayList<String>())
        val owner = object : CallTransportOwner {
            override fun rtcFor(plan: CallTransportPlan, config: CallSessionConfig): RtcCallSession =
                RtcCallSession(RtcCallRequest.of(config), RtcEnv(scheduler, { s, l -> media.createPeer(context, s, l) }, http, steps = { st, d -> line("step $st: $d") }))
            override fun carried(plan: CallTransportPlan) { said += "webrtc" }
            override fun fellBack(plan: CallTransportPlan, reason: String, detail: String) { said += reason; line("fell back: $reason ($detail)") }
        }
        CallTransports.put(CallTransportPlan(callId, CallTransportChoice.Decision(true, null), owner))
        val audio = CountingAudio()
        val config = CallSessionConfig(
            baseUrl = base, publishableKey = key, agentId = session.agentId, webcallAgentId = session.webcallAgentId, wsHost = session.wsHost,
            callVariables = mapOf("who_called" to "customer", "call_reason" to "webrtc_live_fallback"), sdkVariables = emptyMap(),
            identity = identity(), callId = callId,
        )
        val opened = CountDownLatch(1)
        var openAt = 0L
        var outcome = "none"
        val captions = Collections.synchronizedList(ArrayList<String>())
        val events = object : CallSessionEvents {
            override fun onCaption(role: Role, text: String, live: Boolean) { captions += "${role.name.lowercase()}${if (live) "…" else ""}: ${text.take(60)}" }
            override fun onOpen() { openAt = t(); opened.countDown() }
            override fun onFailed(error: String) { outcome = "failed $error"; opened.countDown() }
            override fun onEnded(reason: GraineCallOutcome.EndReason, error: String?) { outcome = "ended ${reason.wire} ${error.orEmpty()}" }
        }
        val start = t()
        val call = CallTransportSession(config, CallSessionDeps(scheduler = scheduler, audio = audio, http = http), events)
        call.start()
        opened.await(15, TimeUnit.SECONDS)
        // The greeting's first seconds.
        val until = SystemClock.elapsedRealtime() + 6_000
        while (SystemClock.elapsedRealtime() < until && audio.samples < 24_000) Thread.sleep(100)
        val hangAt = t()
        call.hangUp()
        Thread.sleep(800)
        line("captions: ${captions.takeLast(4).joinToString(" | ").ifEmpty { "none" }}")
        var ok = true
        ok = check("WebRTC fell back before any audio", said.firstOrNull() == RtcFallback.MINT_FAILED, said.joinToString())
        ok = check("the same call opened on the WebSocket", openAt > 0, if (openAt > 0) "${openAt - start} ms from the start; $outcome" else outcome) && ok
        ok = check("the agent's audio arrived there", audio.samples > 0, "${audio.samples} samples (${"%.2f".format(audio.samples / 24_000.0)} s at 24 kHz), peak ${audio.peak}") && ok
        ok = check("under 20 s", hangAt - start < 20_000, "${hangAt - start} ms to the hang-up") && ok
        return ok
    }
}
