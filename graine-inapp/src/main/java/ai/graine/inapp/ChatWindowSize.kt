package ai.graine.inapp

/*
 * HOW BIG THE CHAT WINDOW OPENS — contract/DESIGN.md "Chat window" → Size.
 *
 * The brand chooses in the dashboard (Embed & Widgets → Appearance → Chat
 * window → Window size), which arrives as the design's `chat.size`; the app
 * may choose otherwise in [GraineConfig.chatSize], and once more for one open
 * with [GraineChat.open]. What a size LOOKS like depends on the screen: a
 * phone gets a sheet or the whole screen, a tablet a panel in the corner. The
 * rule and the numbers are every Graine SDK's (sdk/inapp/theme.ts: chatSizeOf,
 * chatPresentation, CHAT_WINDOW). Pure, and JVM-tested (ChatWindowSizeTest).
 */

/** How big the chat window opens. */
enum class GraineChatSize(
    /** The design's word for it. */
    val wire: String,
) {
    /** The window above the bar, as it has always been. */
    COMPACT("compact"),
    /** A sheet over most of a phone; a panel in the corner of a tablet. */
    LARGE("large"),
    /** The whole screen. */
    FULL("full");

    companion object {
        /** The design's word for a size; null for anything else — absent, or a newer server's word. */
        @JvmStatic
        fun from(wire: Any?): GraineChatSize? {
            val word = (wire as? String)?.trim()?.lowercase() ?: return null
            return values().firstOrNull { it.wire == word }
        }
    }
}

internal object ChatWindowLayout {
    /** Where a chat window goes: above the bar, up from the bottom, in the corner, or all of it. */
    enum class Presentation { WINDOW, SHEET, PANEL, SCREEN }

    /** What closing a sheet, a panel or a screen does — what the chevron does. */
    enum class Close { BACK_TO_CALL, CLOSE_BAR }

    /** A sheet's top edge sits this share of the screen's height below the top inset. */
    const val SHEET_TOP = 0.08
    /** A panel: this wide, and min(PANEL_MAX_HEIGHT, PANEL_HEIGHT × the screen's height) tall, in dp. */
    const val PANEL_WIDTH = 420.0
    const val PANEL_MAX_HEIGHT = 760.0
    const val PANEL_HEIGHT = 0.9
    /** In from the screen's edges, dp. */
    const val PANEL_INSET = 24.0
    /** A window whose shorter side is at least this (dp) is a tablet's. */
    const val TABLET_MIN_SIDE = 600.0
    /** Dragged down this far (dp), a sheet closes. */
    const val CLOSE_DRAG = 80.0
    /** The grab handle: a strip in the header's fill, a short bar in it (dp). */
    const val HANDLE_STRIP = 16.0
    const val HANDLE_WIDTH = 36.0
    const val HANDLE_HEIGHT = 5.0
    /** The page behind a sheet, dimmed. */
    const val SCRIM_ALPHA = 0.4f

    /** The size to open at: the open's own, then the app's, then the design's, else compact. */
    fun size(open: GraineChatSize?, config: GraineChatSize?, token: GraineChatSize?): GraineChatSize =
        open ?: config ?: token ?: GraineChatSize.COMPACT

    /** How a size is drawn in a window this big (dp). */
    fun presentation(size: GraineChatSize, widthDp: Double, heightDp: Double): Presentation = when (size) {
        GraineChatSize.COMPACT -> Presentation.WINDOW
        GraineChatSize.LARGE -> if (minOf(widthDp, heightDp) >= TABLET_MIN_SIDE) Presentation.PANEL else Presentation.SHEET
        GraineChatSize.FULL -> Presentation.SCREEN
    }

    /** A panel's width and height (dp) in a window this tall. */
    fun panelSize(heightDp: Double): Pair<Double, Double> {
        val h = if (heightDp.isFinite()) Math.round(maxOf(0.0, minOf(PANEL_MAX_HEIGHT, PANEL_HEIGHT * heightDp))).toDouble() else 0.0
        return PANEL_WIDTH to h
    }

    /** A sheet's height (px) on a screen this tall with this top inset: its top 8% of the screen below the inset. */
    fun sheetHeight(screenPx: Int, topInsetPx: Int): Int = maxOf(0, screenPx - topInsetPx - Math.round(SHEET_TOP * screenPx).toInt())

    /** Where the bar draws its chat: null while the customer is on the call. */
    fun barPresentation(
        inChat: Boolean, open: GraineChatSize?, config: GraineChatSize?, token: GraineChatSize?, widthDp: Double, heightDp: Double,
    ): Presentation? = if (!inChat) null else presentation(size(open, config, token), widthDp, heightDp)

    /** A drag (dp, and dp per second) let go: does the sheet close? */
    fun dragCloses(dyDp: Double, velocityDpS: Double): Boolean = dyDp > CLOSE_DRAG || velocityDpS > 1000.0

    fun close(canSwitch: Boolean): Close = if (canSwitch) Close.BACK_TO_CALL else Close.CLOSE_BAR
}

/** How a [ChatWindowView] is framed: above the bar, an app's whole screen, a sheet, a tablet's panel, the bar's full screen. */
internal enum class ChatFrame { WINDOW, PAGE, SHEET, PANEL, SCREEN }
