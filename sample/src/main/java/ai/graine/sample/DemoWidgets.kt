package ai.graine.sample

import org.json.JSONObject

/**
 * DEBUG BUILDS ONLY: components as the agent would send them, so the renderer
 * can be looked at without placing a call.
 *
 *   adb shell am start -n ai.graine.sample/.MainActivity --es demoWidget product_list --es demoPreset gallery
 *
 * demoWidget   product_list | plan_comparison | choices | quick_actions | calendar_slots | info_card | unknown
 *              | pending (the loader for a product list) | pending_then_list (the loader, then the list)
 * demoPreset   gallery | cards | list | spotlight | compact
 * demoSelect   one | many
 * demoSurface  auto | screen | sheet | card
 * demoColumns  1 | 2 (Gallery and Cards only)
 *
 * The car showcase is the dashboard's own sample component for this agent,
 * root-relative pictures and all — the SDK resolves them against the agent's origin.
 */
object DemoWidgets {
    fun style(preset: String?, select: String?, surface: String?, columns: String? = null): JSONObject = JSONObject().apply {
        preset?.let { put("preset", it) }
        select?.let { put("select", it) }
        surface?.let { put("surface", it) }
        columns?.toIntOrNull()?.let { put("columns", it) }
    }

    fun frame(kind: String, style: JSONObject): JSONObject = when (kind) {
        "plan_comparison" -> JSONObject("""
            {"widget_id":"plan_picker:demo:1","kind":"plan_comparison","fallback_text":"Two plans: Basic and Family Shield.",
             "data":{"title":"Pick a plan","prompt":"Both renew monthly; cancel any time.","plans":[
               {"id":"basic","name":"Basic","price":"₹299","priceSuffix":"/mo","badge":"Popular","facts":[{"label":"Cover","value":"₹5 lakh"},{"value":"No waiting period"}]},
               {"id":"family","name":"Family Shield","price":"₹499","priceSuffix":"/mo","facts":[{"label":"Cover","value":"₹10 lakh"},{"label":"Members","value":"4"}]}]}}
        """.trimIndent())
        "choices" -> JSONObject("""
            {"widget_id":"ask:demo:1","kind":"choices","fallback_text":"Is this for you or your family?",
             "data":{"title":"Who is it for?","options":[{"id":"me","label":"Just me"},{"id":"family","label":"My family"},{"id":"parents","label":"My parents"}]}}
        """.trimIndent())
        "quick_actions" -> JSONObject("""
            {"widget_id":"actions:demo:1","kind":"quick_actions","fallback_text":"I can call you back or send a link.",
             "data":{"title":"What next?","actions":[{"id":"callback","label":"Call me back"},{"id":"link","label":"Send me a link"}]}}
        """.trimIndent())
        "calendar_slots" -> JSONObject("""
            {"widget_id":"slots:demo:1","kind":"calendar_slots","fallback_text":"Tomorrow at 10 or 11.",
             "data":{"title":"Book a video KYC","note":"All times IST. The call takes about five minutes.","days":[
               {"label":"Tomorrow","slots":["10:00","10:30","11:00","14:00"]},{"label":"Friday","slots":["09:30","16:00"]}]}}
        """.trimIndent())
        "info_card" -> JSONObject("""
            {"widget_id":"status:demo:1","kind":"info_card","fallback_text":"Your PAN is being verified.",
             "data":{"title":"PAN verification","category":"KYC","summary":"Usually done in under two minutes.","actionLabel":"What happens next?",
               "facts":[{"label":"Step","value":"2 of 4"}]}}
        """.trimIndent())
        "unknown" -> JSONObject("""
            {"widget_id":"form:demo:1","kind":"address_form","fallback_text":"I need your current address — you can say it or type it.","data":{}}
        """.trimIndent())
        else -> JSONObject("""
            {"widget_id":"car_showcase:demo:1","kind":"product_list",
             "fallback_text":"Three that fit your budget: Creta SX(O) Turbo at ₹19.2 lakh; Seltos GTX+ at ₹20.4 lakh.",
             "data":{"title":"Three that fit your budget","prompt":"Tap one and I'll tell you more.","selectLabel":"Compare","items":[
               {"id":"sample-1","title":"Creta SX(O) Turbo","subtitle":"Petrol · Automatic · 2026","price":"₹19.2 lakh","imageUrl":"/samples/car-blue.png","facts":["6 airbags","Panoramic sunroof","18.0 kmpl"]},
               {"id":"sample-2","title":"Seltos GTX+","subtitle":"Petrol · Automatic · 2026","price":"₹20.4 lakh","imageUrl":"/samples/car-silver.png","facts":["Ventilated seats","360° camera","17.4 kmpl"]},
               {"id":"sample-3","title":"Grand Vitara Alpha","subtitle":"Strong hybrid · 2026","price":"₹19.9 lakh","imageUrl":"/samples/car-white.png","facts":["Strong hybrid","27.9 kmpl","Waiting 6 weeks"],"unavailable":true}]},
             "on_select":{"path":"compare?ids={id}"}}
        """.trimIndent())
    }.put("style", style)

    fun pending(kind: String): JSONObject = JSONObject().put("widget_id", "car_showcase:demo:1").put("kind", kind)
}
