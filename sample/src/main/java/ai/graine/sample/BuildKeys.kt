package ai.graine.sample

/**
 * The sample's agent. Put your publishable key (Agent → Embed & Widgets) in
 * local.properties, which is never committed:
 *
 *   graine.publishableKey=pk_live_…
 */
object BuildKeys {
    val PUBLISHABLE_KEY: String
        get() = BuildConfig.GRAINE_PUBLISHABLE_KEY.ifEmpty {
            error("Set graine.publishableKey=pk_live_… in local.properties (Agent → Embed & Widgets), then rebuild.")
        }
}
