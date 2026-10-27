package ai.graine.inapp

/*
 * THE MEDIA PATH, KEPT — one answered dial's recovery inside WebRTC (the voice
 * bar's lib/rtc-media-recovery.ts, the same rules: RtcResilience).
 *
 *   setup  answered, the path not up yet: waited for until its deadline (8 s ×
 *          the link's pace), longer while it is still making progress; then an
 *          ICE restart — the same call, a new gathering, a re-INVITE; a second
 *          only when something changed a new gathering can use; then
 *          mediaFailed — the session moves the call to the WebSocket. With
 *          nothing in flight at all, the first restart comes early (2.5 s ×
 *          pace); the hand-over never comes before the deadline all the same
 *   up     the path carries audio
 *   lost   the path went away after it was up: a restart 3 s in (at once on
 *          `failed`), never into no network — time offline is waited out, not
 *          counted, and the network's return gets the path 2 s to heal by itself
 *          first; given up after 15 s × pace with a network (never inside an
 *          open restart's window), or after 30 s without one
 *
 * It never ends a call and never dials. Every timer is the dial's; dispose()
 * silences all of them, and a handler for an older restart or a disposed dial
 * does nothing. All on the scheduler's thread.
 */
internal class RtcRecovery(private val scheduler: CallScheduler, private val host: Host) {
    /** What a restart reports back, on the scheduler. */
    interface Handlers {
        /** Its offer left with this many candidates; none cannot make a path. */
        fun offer(candidates: Int)
        /** Its re-INVITE was answered and the answer applied. */
        fun answered()
        /** Refused, unanswered, or its answer could not be applied: no more restarts on this dial. */
        fun refused(detail: String)
    }

    interface Host {
        /** The link's pace (RtcPace): 1 on a good link. */
        fun pace(): Double
        /** The device has a network. */
        fun online(): Boolean
        /** A re-offer can go now: the call is established, no other offer is in flight, its socket is registered. */
        fun canReoffer(): Boolean
        /** Send restart [n]: false when it could not be sent. */
        fun restart(n: Int, relayOnly: Boolean, on: Handlers): Boolean
        /** Is the setup still moving (checks answered, bytes arriving)? Null: nothing can tell. */
        fun progressing(): Boolean?
        /** Nothing in flight at all: no candidate pair can form. */
        fun nothingInFlight(): Boolean
        /** UDP looks blocked and a relay-only restart can be made. */
        fun udpBlocked(): Boolean
        /** This dial's path is gone for good. Said once. */
        fun mediaFailed(lost: Boolean, detail: String)
        /** An answered call that had its audio is reconnecting — or is not any more. */
        fun reconnecting(active: Boolean)
        /** The path came up after [restarts] restarts, [afterMs] after the trouble began. */
        fun recovered(restarts: Int, afterMs: Long)
        fun log(line: String)
    }

    private enum class Phase { IDLE, SETUP, UP, LOST, DONE }

    private var phase = Phase.IDLE
    private val timers = HashMap<String, Armed>()
    private var restarts = 0
    private var inFlight = false
    private var refused = false
    private var relayOnlyTried = false
    private var netEpoch = 0
    private var epochAtRestart = 0
    private var lostFailed = false
    private var troubleAt = 0L
    /** A setup is never handed over before this — its deadline from the answer — early restart or not. */
    private var notBefore = 0L
    private var deadlineMs = 0L
    private var extendedCapMs = 0L
    private var extendedMs = 0L

    val restartsMade: Int get() = restarts
    val restartInFlight: Boolean get() = inFlight
    val active: Boolean get() = phase == Phase.SETUP || phase == Phase.LOST

    private fun now() = scheduler.now()

    /** The dial was answered: the setup's clock starts. */
    fun answered() {
        if (phase != Phase.IDLE) return
        phase = Phase.SETUP
        troubleAt = now()
        val pace = host.pace()
        notBefore = now() + RtcResilience.setupDeadlineMs(pace)
        openWindow(RtcResilience.setupDeadlineMs(pace), RtcResilience.setupExtendedCapMs(pace))
        arm("early", RtcResilience.earlyRestartMs(pace)) { earlyLook() }
    }

    /** The path carries audio. */
    fun pathUp() {
        if (phase != Phase.SETUP && phase != Phase.LOST) return
        val was = phase
        phase = Phase.UP
        clearAll()
        if (was == Phase.LOST) host.reconnecting(false)
        if (restarts > 0) host.recovered(restarts, now() - troubleAt)
        // The next trouble starts its own count; a refusal stays (the far side would refuse again).
        restarts = 0
        relayOnlyTried = false
        inFlight = false
    }

    /** ICE went disconnected ([failed] false) or failed (true). Before the path was ever up, failed is acted on at once. */
    fun pathLost(failed: Boolean) {
        when (phase) {
            Phase.SETUP -> if (failed && !inFlight) { clear("deadline"); act("ICE failed before the path came up") }
            Phase.LOST -> if (failed && !lostFailed) {
                // A disconnection that became a failure: ICE gave up, so the restart need not wait.
                lostFailed = true
                if (host.online() && !inFlight) { clear("restartAt"); lostRestart() }
            }
            Phase.UP -> {
                phase = Phase.LOST
                lostFailed = failed
                troubleAt = now()
                restarts = 0
                relayOnlyTried = false
                host.reconnecting(true)
                if (!host.online()) { arm("offlineHold", RtcResilience.OFFLINE_HOLD_MS) { giveUp("the network did not come back") }; return }
                if (failed) {
                    // At once: ICE has given up on this path.
                    arm("giveUp", RtcResilience.pathLostGiveUpMs(host.pace())) { lostGiveUp() }
                    lostRestart()
                } else {
                    armLostClock(RtcResilience.RESTART_AFTER_MS)
                }
            }
            else -> Unit
        }
    }

    /** The network changed: gone ([online] false), back, or another network. */
    fun networkChanged(online: Boolean) {
        netEpoch++
        if (phase != Phase.LOST) return
        if (!online) {
            clear("restartAt"); clear("giveUp"); clear("restartWindow")
            if (!timers.containsKey("offlineHold")) arm("offlineHold", RtcResilience.OFFLINE_HOLD_MS) { giveUp("the network did not come back") }
            return
        }
        clear("offlineHold")
        armLostClock(RtcResilience.ONLINE_SETTLE_MS)
    }

    /** The dial is over: every timer goes, and nothing fires again. A reconnect in progress is told over, once. */
    fun dispose() {
        val wasLost = phase == Phase.LOST
        phase = Phase.DONE
        clearAll()
        if (wasLost) host.reconnecting(false)
    }

    // ── setup ──────────────────────────────────────────────────────────────

    private fun openWindow(deadline: Long, cap: Long) {
        deadlineMs = deadline
        extendedCapMs = cap
        extendedMs = 0
        arm("deadline", deadline) { judge() }
    }

    private fun earlyLook() {
        if (phase != Phase.SETUP || restarts > 0 || inFlight || !host.nothingInFlight()) return
        if (!host.online() || !host.canReoffer()) return   // the deadline looks again
        sendRestart("nothing to check ${now() - troubleAt} ms after the answer")
    }

    private fun judge() {
        if (phase != Phase.SETUP) return
        val progressing = host.progressing() == true
        if (progressing && deadlineMs + extendedMs < extendedCapMs) {
            if (extendedMs == 0L) host.log("media: no path $deadlineMs ms in, still making progress; waiting (at most $extendedCapMs ms)")
            extendedMs += RtcResilience.PROGRESS_LOOK_MS
            arm("deadline", RtcResilience.PROGRESS_LOOK_MS) { judge() }
            return
        }
        act(if (restarts > 0) "no path ${deadlineMs + extendedMs} ms after restart $restarts" else "no path ${deadlineMs + extendedMs} ms after the answer")
    }

    private fun act(why: String) {
        if (phase != Phase.SETUP) return
        val udp = host.udpBlocked()
        val permitted = !inFlight && RtcResilience.restartPermitted(restarts, netEpoch != epochAtRestart, udp, relayOnlyTried, refused)
        if (permitted) {
            if (host.online() && host.canReoffer()) {
                if (sendRestart(why)) return
            } else if (deadlineMs + extendedMs < extendedCapMs) {
                // Not now — no network, or the socket reconnecting: tried again shortly, inside the cap.
                extendedMs += RtcResilience.RESTART_RETRY_MS
                arm("deadline", RtcResilience.RESTART_RETRY_MS) { act(why) }
                return
            }
        }
        // Never handed over before the deadline: an early restart that failed waits it out.
        val wait = notBefore - now()
        if (wait > 0) { arm("deadline", wait) { act(why) }; return }
        giveUp(why)
    }

    // ── lost ───────────────────────────────────────────────────────────────

    private fun armLostClock(restartInMs: Long) {
        arm("giveUp", RtcResilience.pathLostGiveUpMs(host.pace())) { lostGiveUp() }
        if (!inFlight) arm("restartAt", restartInMs) { lostRestart() }
    }

    /** The lost path's time is up — unless a restart's window is still open: that path may yet come up, and its window is bounded. */
    private fun lostGiveUp() {
        if (timers.containsKey("restartWindow")) { arm("giveUp", RtcResilience.RESTART_RETRY_MS) { lostGiveUp() }; return }
        giveUp("the path did not come back")
    }

    private fun lostRestart() {
        if (phase != Phase.LOST || inFlight || !host.online()) return
        val permitted = RtcResilience.restartPermitted(restarts, netEpoch != epochAtRestart, host.udpBlocked(), relayOnlyTried, refused)
        if (!permitted) return            // the give-up decides
        if (!host.canReoffer()) { arm("restartAt", RtcResilience.RESTART_RETRY_MS) { lostRestart() }; return }
        sendRestart(if (lostFailed) "ICE failed" else "the path was lost ${now() - troubleAt} ms ago")
    }

    // ── a restart ──────────────────────────────────────────────────────────

    private fun sendRestart(why: String): Boolean {
        val n = restarts + 1
        val relayOnly = host.udpBlocked() && !relayOnlyTried
        val stale = { phase == Phase.DONE || restarts != n || (phase != Phase.SETUP && phase != Phase.LOST) }
        restarts = n
        inFlight = true
        epochAtRestart = netEpoch
        if (relayOnly) relayOnlyTried = true
        host.log("media: ICE restart $n: $why${if (relayOnly) " (the relays over TCP and TLS only: UDP looks blocked)" else ""}")
        val sent = try {
            host.restart(n, relayOnly, object : Handlers {
                override fun offer(candidates: Int) {
                    if (stale() || candidates > 0) return
                    host.log("media: ICE restart $n gathered nothing")
                    over(n)
                }
                override fun answered() {
                    if (stale()) return
                    inFlight = false
                    host.log("media: ICE restart $n answered")
                }
                override fun refused(detail: String) {
                    if (stale()) return
                    inFlight = false
                    this@RtcRecovery.refused = true
                    host.log("media: ICE restart $n refused ($detail): no more on this call")
                    over(n)
                }
            })
        } catch (e: Exception) {
            host.log("media: ICE restart $n could not be sent (${e.javaClass.simpleName})")
            false
        }
        if (!sent) {
            restarts = n - 1
            inFlight = false
            if (relayOnly) relayOnlyTried = false
            return false
        }
        val pace = host.pace()
        if (phase == Phase.SETUP) openWindow(RtcResilience.restartWindowMs(pace), RtcResilience.restartExtendedCapMs(pace))
        else arm("restartWindow", RtcResilience.restartWindowMs(pace)) { over(n) }
        return true
    }

    /** Restart [n] will not bring the path back (refused, nothing gathered, or its window ran out). */
    private fun over(n: Int) {
        if (restarts != n) return
        when (phase) {
            Phase.SETUP -> { clear("deadline"); act("restart $n did not bring the path up") }
            Phase.LOST -> {
                clear("restartWindow")
                if (!refused) inFlight = false
                lostRestart()
            }
            else -> Unit
        }
    }

    private fun giveUp(why: String) {
        if (phase != Phase.SETUP && phase != Phase.LOST) return
        val wasLost = phase == Phase.LOST
        phase = Phase.DONE
        clearAll()
        host.log("media: $why${if (restarts > 0) " after $restarts ICE restart${if (restarts > 1) "s" else ""}" else ""}")
        if (wasLost) host.reconnecting(false)
        host.mediaFailed(wasLost, why)
    }

    // ── plumbing ───────────────────────────────────────────────────────────

    /** One armed timer: cancelled by [clear], and a task that finds itself replaced does nothing (a cancel can race a task already due). */
    private class Armed { var cancel: () -> Unit = {} }

    private fun arm(name: String, ms: Long, task: () -> Unit) {
        clear(name)
        val armed = Armed()
        timers[name] = armed
        armed.cancel = scheduler.schedule(Math.max(0L, ms)) {
            if (timers[name] !== armed) return@schedule
            timers.remove(name)
            if (phase != Phase.DONE) task()
        }
    }

    private fun clear(name: String) {
        timers.remove(name)?.cancel?.invoke()
    }

    private fun clearAll() {
        val all = timers.values.toList()
        timers.clear()
        for (armed in all) armed.cancel()
    }
}
