package ai.graine.inapp

import org.json.JSONArray
import org.json.JSONObject

/*
 * THE COMPONENTS THE AGENT DRAWS — as data and as a small state machine, with
 * no Android in it. GraineWidgetView is the drawing; everything it decides is
 * decided here, so every rule is a JVM test:
 *
 *   GraineWidget    a frame from the page, adopted exactly as the React Native
 *                   SDK's useGraineWidget adopts it
 *   WidgetContent   what widget.tsx reduces a kind to — rows, chips, days —
 *                   and the answer each tap sends (the structured payload the
 *                   runtime's resultToText reads, and the sentence the agent
 *                   hears)
 *   WidgetFlow      useGraineWidget: pending → held → shown → answered or
 *                   closed, with the loader's minimum and the give-up timers
 *                   as deadlines rather than threads
 */

/** A component the agent put on screen, adopted from the page's frame. */
data class GraineWidget(
    val id: String,
    /** "product_list", "plan_comparison", "choices", … or whatever the agent declares. */
    val kind: String,
    /** Root-relative pictures already resolved against the agent's origin. */
    val data: JSONObject,
    /** What the agent would have said instead. Drawn when the kind is unknown. */
    val fallbackText: String,
    /** How the dashboard asked for it to look. Already allowlisted. */
    val style: WidgetStyle,
    /** What the app should do on a tap, beyond telling the agent. Already allowlisted. */
    val onSelect: WidgetSelectAction,
) {
    companion object {
        /**
         * The page's `widget` object → a component, or null when it names no
         * `widget_id` (a frame the SDK cannot answer is not one to draw).
         */
        @JvmStatic
        fun adopt(raw: JSONObject?, baseUrl: String): GraineWidget? {
            if (raw == null) return null
            val wid = raw.opt("widget_id")
            if (!Js.truthy(wid)) return null
            val data = (raw.opt("data") as? JSONObject)?.let { Widgets.resolveImages(it, baseUrl) } ?: JSONObject()
            val onSelect = raw.opt("on_select").let { if (Js.nullish(it)) raw.opt("onSelect") else it }
            return GraineWidget(
                id = Js.str(wid),
                kind = raw.opt("kind").let { if (Js.truthy(it)) Js.str(it) else "" },
                data = data,
                fallbackText = raw.opt("fallback_text").let { if (Js.truthy(it)) Js.str(it) else "" },
                style = Widgets.normaliseStyle(raw.opt("style")),
                onSelect = Widgets.normaliseSelectAction(onSelect),
            )
        }
    }
}

/** What a tap sends: the structured payload, and the sentence that is the customer's turn. */
/** A component the customer answered: what they chose, and the sentence the agent heard as their turn. */
data class GraineAnswer(val widget: GraineWidget, val data: Map<String, Any?>, val summary: String)

data class WidgetAnswer(val data: Map<String, Any?>, val summary: String)

/**
 * One kind, reduced to what the renderer draws — widget.tsx's normalisation,
 * line for line. Three dashboard kinds (product list, plan comparison, info
 * card) share one row shape; chips cover the agent's `choices` and the
 * dashboard's `quick_actions`; `calendar_slots` is days and their times.
 */
internal class WidgetContent(val widget: GraineWidget) {
    /** One normalised row. */
    data class Row(
        val id: String, val title: String, val subtitle: String, val price: String,
        val facts: List<String>, val image: String, val unavailable: Boolean, val selectLabel: String,
    )
    data class Chip(val id: String, val label: String)
    data class Day(val label: String, val slots: List<String>)

    private val data = widget.data
    private val kind = widget.kind

    /** Chips: the agent's own `options`, or quick_actions' `actions`. */
    private val options: List<Any?> = when {
        data.opt("options") is JSONArray -> list(data.opt("options"))
        kind == "quick_actions" && data.opt("actions") is JSONArray -> list(data.opt("actions"))
        else -> emptyList()
    }
    val isQuickActions: Boolean = kind == "quick_actions" && options.isNotEmpty()

    private enum class Shape { PLANS, CARD, ITEMS }
    private val shape: Shape = when {
        kind == "plan_comparison" && data.opt("plans") is JSONArray -> Shape.PLANS
        kind == "info_card" && (Js.truthy(data.opt("title")) || Js.truthy(data.opt("summary"))) -> Shape.CARD
        else -> Shape.ITEMS
    }

    /** The button label and the verb for "choose several". */
    val selectLabel: String = when (shape) {
        Shape.PLANS -> "Select"
        Shape.CARD -> or(data.opt("actionLabel"), "Tell me more")
        Shape.ITEMS -> or(data.opt("selectLabel"), "Choose")
    }

    /** The items as the kind names them, before they became rows (the RN `normalised.items`). */
    private val items: List<JSONObject> = when (shape) {
        Shape.PLANS -> list(data.opt("plans")).map { pl ->
            val p = pl as? JSONObject
            JSONObject().apply {
                put("id", p?.opt("id") ?: JSONObject.NULL)
                put("title", p?.opt("name") ?: JSONObject.NULL)
                put("subtitle", p?.opt("badge") ?: JSONObject.NULL)
                val price = p?.opt("price")
                put("price", if (Js.truthy(price)) Js.str(price) + p?.opt("priceSuffix").let { if (Js.nullish(it)) "" else Js.str(it) } else "")
                put("facts", JSONArray(facts(p?.opt("facts"))))
                put("selectLabel", p?.opt("selectLabel") ?: JSONObject.NULL)
            }
        }
        Shape.CARD -> listOf(JSONObject().apply {
            put("id", "card")
            put("title", data.opt("title") ?: JSONObject.NULL)
            put("subtitle", data.opt("category") ?: JSONObject.NULL)
            put("imageUrl", data.opt("imageUrl") ?: JSONObject.NULL)
            put("facts", JSONArray((listOf(data.opt("summary")) + facts(data.opt("facts"))).filter { Js.truthy(it) }))
        })
        // A bare value in `items` has no id or title of its own: JS reads `it?.title ?? it?.id ?? i`
        // on it and gets the index, so it is an empty object here.
        Shape.ITEMS -> list(data.opt("items")).map { it as? JSONObject ?: JSONObject() }
    }

    val rows: List<Row> = items.take(8).mapIndexed { i, it ->
        Row(
            id = Js.str(firstDefined(it.opt("id"), it.opt("title")) ?: i),
            title = Js.str(firstDefined(it.opt("title"), it.opt("id")) ?: i),
            subtitle = it.opt("subtitle").let { v -> if (Js.truthy(v)) Js.str(v) else "" },
            price = it.opt("price").let { v -> if (Js.truthy(v)) Js.str(v) else "" },
            facts = (it.opt("facts") as? JSONArray)?.let { f -> (0 until f.length()).map { k -> Js.str(f.opt(k)) }.take(3) } ?: emptyList(),
            image = it.opt("imageUrl").let { v -> if (Js.truthy(v)) Js.str(v) else "" },
            unavailable = it.opt("unavailable") == true,
            selectLabel = it.opt("selectLabel").let { v -> if (Js.truthy(v)) Js.str(v) else selectLabel },
        )
    }

    val isProducts: Boolean = rows.isNotEmpty() &&
        (kind == "product_list" || kind == "products" || kind == "plan_comparison" || kind == "info_card" || options.isEmpty())
    val isChips: Boolean = (kind == "choices" || isQuickActions) && options.isNotEmpty()
    val isCalendar: Boolean = kind == "calendar_slots" && (data.opt("days") as? JSONArray)?.length()?.let { it > 0 } == true
    /** Something this build can draw. A kind it cannot draws the fallback sentence, on a card. */
    val drawable: Boolean = isProducts || isChips || isCalendar

    val chips: List<Chip> = options.map { o ->
        val obj = o as? JSONObject
        val label = Js.strOrEmpty(firstDefined(obj?.opt("label"), obj?.opt("title")) ?: o)
        Chip(id = Js.str(firstDefined(obj?.opt("id")) ?: label), label = label)
    }

    val days: List<Day> = if (!isCalendar) emptyList() else list(data.opt("days")).take(4).mapIndexed { di, d ->
        val o = d as? JSONObject
        Day(
            label = Js.str(firstDefined(o?.opt("label"), o?.opt("id")) ?: "Day ${di + 1}"),
            slots = (o?.opt("slots") as? JSONArray)?.let { s -> (0 until s.length()).map { Js.str(s.opt(it)) } } ?: emptyList(),
        )
    }.filter { it.slots.isNotEmpty() }.map { it.copy(slots = it.slots.take(8)) }

    val title: String = data.opt("title").let { if (Js.truthy(it)) Js.str(it) else "" }
    val prompt: String = data.opt("prompt").let { if (Js.truthy(it)) Js.str(it) else "" }
    val note: String = data.opt("note").let { if (Js.truthy(it)) Js.str(it) else "" }

    /** The surface actually drawn: an undrawable kind takes over nothing. */
    fun surface(look: WidgetLook): String = if (drawable) look.surface else "card"

    /** Choosing several applies only where there is something to tick. */
    fun many(look: WidgetLook): Boolean = look.select == "many" && drawable

    /** The payload for one row, per kind — what the runtime's resultToText reads. */
    private fun toSubmit(r: Row): Map<String, Any?> = when (shape) {
        Shape.PLANS -> linkedMapOf("id" to r.id, "name" to r.title, "price" to r.price)
        Shape.CARD -> linkedMapOf("action" to or(data.opt("actionLabel"), "Tell me more"))
        Shape.ITEMS -> linkedMapOf("id" to r.id, "title" to r.title, "price" to r.price)
    }

    /** One row tapped. */
    fun pick(index: Int): WidgetAnswer {
        val r = rows[index]
        val summary = when (shape) {
            Shape.PLANS -> "I'll take ${r.title}${if (r.price.isNotEmpty()) " at ${r.price}" else ""}."
            Shape.CARD -> or(data.opt("actionLabel"), "Tell me more about this.")
            Shape.ITEMS -> "I'll take a look at ${r.title}${if (r.price.isNotEmpty()) " at ${r.price}" else ""}."
        }
        return WidgetAnswer(toSubmit(r), summary)
    }

    /**
     * Several at once, as ONE answer: every item, the flattened ids and
     * titles an in-app action's arguments usually want, and one sentence that
     * names them the way a person would.
     */
    fun pickMany(ids: List<String>): WidgetAnswer {
        val chosen = ids.mapNotNull { id -> rows.firstOrNull { it.id == id } }
        val titles = chosen.map { it.title }.filter { it.isNotEmpty() }
        return WidgetAnswer(
            linkedMapOf(
                "items" to chosen.map { toSubmit(it) },
                "ids" to chosen.joinToString(",") { it.id },
                "titles" to titles.joinToString(", "),
                "count" to chosen.size,
            ),
            Widgets.manySummary(titles, selectLabel.lowercase()),
        )
    }

    /** A chip tapped. Both shapes' keys, so the agent hears a sentence either way. */
    fun pickChip(chip: Chip): WidgetAnswer = WidgetAnswer(
        if (isQuickActions) linkedMapOf("id" to chip.id, "label" to chip.label) else linkedMapOf("chosen" to chip.id, "label" to chip.label),
        "I'll go with ${chip.label}.",
    )

    /** A time tapped: the day and the slot, which is what resultToText("calendar_slots") reads. */
    fun pickSlot(day: Day, slot: String): WidgetAnswer =
        WidgetAnswer(linkedMapOf("day" to day.label, "slot" to slot), "${day.label} at $slot, please.")

    private companion object {
        fun list(v: Any?): List<Any?> = (v as? JSONArray)?.let { a -> (0 until a.length()).map { a.opt(it) } } ?: emptyList()

        /** `a ?? b ?? …`: the first value that is neither undefined nor null. */
        fun firstDefined(vararg vs: Any?): Any? = vs.firstOrNull { !Js.nullish(it) }

        /** `String(v || fallback)`. */
        fun or(v: Any?, fallback: String): String = if (Js.truthy(v)) Js.str(v) else fallback

        /** Fact rows: `{label, value}` as "label value", else the value on its own. */
        fun facts(v: Any?): List<String> = list(v).map { f ->
            val o = f as? JSONObject
            val label = o?.opt("label")
            if (Js.truthy(label)) "${Js.str(label)} ${Js.str(o?.opt("value"))}" else Js.strOrEmpty(o?.opt("value"))
        }
    }
}

/**
 * useGraineWidget, as a state machine with time passed in.
 *
 *   pending   the agent called for a component; its content is loading. A
 *             loader stands where it will appear, for at most
 *             [Widgets.PENDING_WIDGET_MS].
 *   held      it arrived within [Widgets.MIN_LOADER_MS] of the loader going
 *             up, so the loader finishes being seen first. Closing now
 *             cancels it — a component must not appear after the customer
 *             closed the wait.
 *   shown     [widget].
 *   answered  a tap went to the agent; the choice stays as one line, saying
 *             "thinking" until the agent starts ([onAgentStarted]) or
 *             [Widgets.AWAITING_REPLY_MS] passes.
 *   closed    dismissed without choosing: kept in [closed] for [reopen], and
 *             the agent is told quietly (the caller sends the report).
 *
 * The owner calls [tick] at [nextDeadline]; nothing here owns a timer.
 */
internal class WidgetFlow(private val baseUrl: String) {
    data class Pending(val id: String, val kind: String)

    /** What to put on the wire for an answer, and the app's own action to run first. */
    data class Submission(val widgetId: String, val data: Map<String, Any?>, val summary: String, val action: SelectCall?)

    /** The data of the quiet `component_dismissed` event, exactly as the RN SDK sends it. */
    data class Dismissal(val toolName: String, val kind: String, val title: String) {
        fun eventData(): Map<String, Any?> = linkedMapOf("tool_name" to toolName, "kind" to kind, "title" to title, "answered" to false)
    }

    var widget: GraineWidget? = null; private set
    var answered: GraineAnswer? = null; private set
    var loading: Pending? = null; private set
    var awaitingReply: Boolean = false; private set
    var closed: GraineWidget? = null; private set

    private var shownAt: Long? = null
    private var loadingUntil: Long? = null
    private var held: GraineWidget? = null
    private var heldUntil: Long? = null
    private var awaitingUntil: Long? = null

    /** A component drawn while nothing was listening, picked up on mount. */
    fun seed(raw: JSONObject?) {
        val w = GraineWidget.adopt(raw, baseUrl) ?: return
        if (widget?.id != w.id) widget = w
    }

    /** A component is on its way. */
    fun onPending(hint: JSONObject?, now: Long) {
        shownAt = now
        loading = Pending(Js.strOrEmpty(hint?.opt("widget_id")), Js.strOrEmpty(hint?.opt("kind")))
        loadingUntil = now + Widgets.PENDING_WIDGET_MS
    }

    /** The agent drew one. Returns true when it is on screen now (false: held, or not a component). */
    fun onWidget(raw: JSONObject?, now: Long): Boolean {
        // Any component the agent draws is the agent answering.
        onAgentStarted()
        val next = GraineWidget.adopt(raw, baseUrl) ?: return false
        loadingUntil = null
        val seen = shownAt?.let { now - it } ?: Widgets.MIN_LOADER_MS
        if (seen >= Widgets.MIN_LOADER_MS) { show(next); return true }
        held = next
        heldUntil = now + (Widgets.MIN_LOADER_MS - seen)
        return false
    }

    private fun show(next: GraineWidget) {
        shownAt = null
        held = null; heldUntil = null
        loading = null; loadingUntil = null
        closed = null
        widget = next
        // A new question replaces the last answer, or stale lines stack up.
        answered = null
    }

    /** The agent started speaking (a caption, a component): stop saying we are waiting. */
    fun onAgentStarted() {
        awaitingReply = false
        awaitingUntil = null
    }

    /**
     * The customer answered. `summary` is replaced by the component's own
     * `say` when it runs an in-app action; the action itself is the caller's to
     * run, first, without waiting on it.
     */
    fun submit(data: Map<String, Any?>, summary: String, now: Long): Submission? {
        val w = widget ?: return null
        val item = LinkedHashMap(data).apply { put("summary", summary) }
        val call = Widgets.selectCall(w.onSelect, item)
        val said = if (call != null && w.onSelect.say != null) Widgets.fillSelectTemplate(w.onSelect.say, item) else summary
        widget = null
        answered = GraineAnswer(w, data, said)
        awaitingReply = true
        awaitingUntil = now + Widgets.AWAITING_REPLY_MS
        return Submission(w.id, data, said, call)
    }

    /**
     * Closed without choosing — or "Clear" on the answered line. Returns what
     * to tell the agent when a component was actually on screen; null when
     * there was nothing to report.
     */
    fun dismiss(): Dismissal? {
        held = null; heldUntil = null
        shownAt = null
        val w = widget
        val report = w?.let {
            Dismissal(
                toolName = it.id.split(":")[0],
                kind = it.kind,
                title = Js.strOrEmpty(it.data.opt("title")),
            )
        }
        closed = w
        widget = null
        answered = null
        loading = null; loadingUntil = null
        awaitingReply = false; awaitingUntil = null
        return report
    }

    /** Put the last closed component back, exactly as it was. No tool call, no second fetch. */
    fun reopen(): Boolean {
        val c = closed ?: return false
        widget = c
        closed = null
        answered = null
        return true
    }

    /** A call ended, or a new one began: nothing from the last one belongs on screen. */
    fun reset() {
        widget = null; answered = null; loading = null; closed = null
        awaitingReply = false
        shownAt = null; loadingUntil = null; held = null; heldUntil = null; awaitingUntil = null
    }

    /** Apply every deadline that has passed. Returns true when anything visible changed. */
    fun tick(now: Long): Boolean {
        var changed = false
        heldUntil?.let { if (now >= it) { held?.let { h -> show(h) }; changed = true } }
        loadingUntil?.let { if (now >= it) { loading = null; loadingUntil = null; changed = true } }
        awaitingUntil?.let { if (now >= it) { awaitingReply = false; awaitingUntil = null; changed = true } }
        return changed
    }

    /** When [tick] next has something to do, or null. */
    fun nextDeadline(): Long? = listOfNotNull(heldUntil, loadingUntil, awaitingUntil).minOrNull()
}
