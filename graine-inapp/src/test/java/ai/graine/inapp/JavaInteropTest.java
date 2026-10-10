package ai.graine.inapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import org.junit.Test;

/**
 * The SDK as a Java app sees it. Most bank apps still have Java in them, so
 * this file COMPILING is half the test: every call below is one a Java
 * integrator writes on the first day, with only the arguments they care about.
 */
public class JavaInteropTest {
    @Test public void aJavaAppConfiguresTheSdkWithOnlyWhatItNeeds() {
        GraineConfig config = new GraineConfig("https://www.graine.ai", "pk_live_test");
        assertEquals("https://www.graine.ai", config.getBaseUrl());
        assertTrue(config.getEndOnBackground());
    }

    @Test public void actionResultsAreStaticFactories() {
        assertEquals(ActionResult.Status.OK, ActionResult.ok().getStatus());
        assertEquals("Opened.", ActionResult.ok("Opened.").getMessage());
        assertEquals(ActionResult.Status.REFUSED, ActionResult.refused("Not here.").getStatus());
        assertEquals(ActionResult.Status.ERROR, ActionResult.error("Broke.").getStatus());
    }

    @Test public void aScreenIsBuiltWithoutSpellingOutEveryDefault() {
        GraineField pan = new GraineField("pan", "PAN", "", GraineField.Status.EMPTY);
        GraineScreen screen = new GraineScreen("kyc_documents", "Upload your documents",
                new GraineScreen.Journey("KYC", 2, 4), Collections.singletonList(pan));
        assertEquals("kyc_documents", screen.getScreen());
        assertEquals(1, screen.getFields().size());
    }

    @Test public void aJavaAppMapsItsFontsWithoutSpellingOutTheRest() {
        GraineConfig config = new GraineConfig("https://www.graine.ai", "pk_live_test",
                Collections.<String, Object>emptyMap(), null, null, true, new GraineConfig.Timing(), false, null,
                Collections.<String, android.graphics.Typeface>emptyMap());
        assertTrue(config.getFonts().isEmpty());
    }

    @Test public void aDesignIsReadPickedAndItsGlyphAskedForFromJava() throws Exception {
        String json = "{\"config\":{\"preset\":\"graine-classic\",\"scheme\":\"dark\"},"
                + "\"light\":{\"colors\":{\"accent\":\"#7C3AED\"}},\"dark\":{\"colors\":{\"accent\":\"#7c3aed\"}}}";
        ResolvedDesign design = ResolvedDesign.parse(json);
        ThemeTokens tokens = design.pick(Boolean.FALSE);
        assertEquals("dark", tokens.getScheme());
        assertEquals("#7C3AED", tokens.getColors().getAccent());
        assertTrue(tokens.isDark());
        assertEquals("a design that says nothing about chrome is a plain surface", "none", tokens.getChrome());
        assertEquals(null, ResolvedDesign.parse("not a design"));
        assertEquals("https://www.graine.ai/api/embed/glyph?id=waveform&c=FFFFFF&s=96&w=2&v=2",
                Design.glyphUrl("https://www.graine.ai", "waveform", "#FFFFFF", 96.0));
        assertEquals(28.0, Design.launcherCorner("circle", Design.LAUNCHER_SIZE, Design.CLASSIC_LIGHT.getShape().getRadius()), 0.0);
    }

    @Test public void tokensAJavaAppBuiltBeforeWindowChromeStillBuildAsAPlainSurface() {
        // The constructor as 0.3.0 had it, without `chrome`: it still compiles, and draws no window.
        ThemeTokens c = Design.CLASSIC_LIGHT;
        ThemeTokens t = new ThemeTokens(c.getV(), c.getPreset(), c.getScheme(), c.getColors(), c.getFont(), c.getShape(),
                c.getSpacing(), c.getElevation(), c.getMotion(), c.getIcons(), c.getGlass(), c.getBubbles());
        assertEquals("none", t.getChrome());
        assertEquals(c, t);
    }

    @Test public void theCallsPhaseIsReadFromJava() {
        // The customer finished a line and the agent has not answered: the bar's "Thinking…".
        assertEquals(CallPhase.THINKING, CallPhase.of(false, Connectivity.READY, false, true, true, false, true));
        assertEquals(CallPhase.IDLE, CallPhase.of(false, Connectivity.READY, false, true, false, false, false));
    }

    @Test public void aListenerOverridesOnlyTheCallbackItWants() {
        final boolean[] ended = {false};
        GraineListener listener = new GraineListener() {
            @Override public void onEnded() { ended[0] = true; }
        };
        listener.onEnded();
        // A Java default method, not an abstract one the app is forced to write.
        listener.onError("unheard");
        // 0.5.0's new callback too: an app written before it still compiles, and need not hear it.
        listener.onConnectProgress(ConnectStage.RETRYING, true, false);
        assertTrue(ended[0]);
    }

    @Test public void connectStagesAndTheLogosPlateAreReadFromJava() {
        assertEquals(ConnectStage.DIALLING, ConnectStage.from("dialling"));
        assertEquals("failed ends a connect; it is not a stage", null, ConnectStage.from("failed"));
        assertEquals(4000L, new GraineConfig.Timing().getSlowConnectMs());
        assertEquals(LogoPlate.WHITE, LogoPlate.from("white"));
        assertEquals(LogoPlate.ACCENT, LogoPlate.from("WHITE"));
        assertEquals(26, Logo.box("circle", 38.0));
        Logo.Plate plate = Logo.plate(LogoPlate.WHITE, "#318CE7", "#E5E5E5");
        assertEquals("#FFFFFF", plate.getFill());
        assertEquals(1.0, plate.getOutline().getWidth(), 0.0);
        // 0.4.0's four-argument constructor still builds, on the accent.
        assertEquals(LogoPlate.ACCENT, new NativeAppearance("#318CE7", "Ring", null, 18.0).getLogoPlate());
    }

    @Test public void aJavaAppConfiguresCallsAndHearsTheirOutcomes() {
        // Every argument has a default: a Java app names only what it cares about.
        GraineCallsConfig config = new GraineCallsConfig("Acme Support");
        assertEquals("Acme Support", config.getDisplayName());
        assertEquals(30_000L, config.getRingTimeoutMs());
        assertEquals("Assistant", new GraineCallsConfig().getDisplayName());

        final String[] heard = {null, null};
        GraineCallListener listener = new GraineCallListener() {
            @Override public void onCallOutcome(String callId, GraineCallOutcome outcome) {
                heard[0] = callId;
                heard[1] = outcome.getWire();
            }
        };
        // Default methods: an app written against one callback still compiles as more are added.
        listener.onCallState(GraineCallState.RINGING, null);
        listener.onAgentSpeaking(true);
        listener.onCallOutcome("call_1", GraineCallOutcome.MISSED);
        assertEquals("call_1", heard[0]);
        assertEquals("missed", heard[1]);

        assertEquals("local", GraineCallOutcome.ended(GraineCallOutcome.EndReason.LOCAL).getEndReason().getWire());
        assertEquals("failed", GraineCallOutcome.failed("no network").getWire());
        assertEquals(GraineCallOutcome.Kind.ANSWERED, GraineCallOutcome.ANSWERED.getKind());
        assertEquals("ai.graine.inapp.action.ANSWER_CALL", GraineCalls.ACTION_ANSWER);
        GraineCall call = new GraineCall("c", GraineCall.Direction.INCOMING, "Acme", "payment_due",
                Collections.singletonMap("who_called", "agent"));
        assertEquals(GraineCall.Direction.INCOMING, call.getDirection());
    }
}
