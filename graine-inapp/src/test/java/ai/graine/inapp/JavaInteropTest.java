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
        assertEquals(null, ResolvedDesign.parse("not a design"));
        assertEquals("https://www.graine.ai/api/embed/glyph?id=waveform&c=FFFFFF&s=96&w=2&v=2",
                Design.glyphUrl("https://www.graine.ai", "waveform", "#FFFFFF", 96.0));
        assertEquals(28.0, Design.launcherCorner("circle", Design.LAUNCHER_SIZE, Design.CLASSIC_LIGHT.getShape().getRadius()), 0.0);
    }

    @Test public void aListenerOverridesOnlyTheCallbackItWants() {
        final boolean[] ended = {false};
        GraineListener listener = new GraineListener() {
            @Override public void onEnded() { ended[0] = true; }
        };
        listener.onEnded();
        // A Java default method, not an abstract one the app is forced to write.
        listener.onError("unheard");
        assertTrue(ended[0]);
    }
}
