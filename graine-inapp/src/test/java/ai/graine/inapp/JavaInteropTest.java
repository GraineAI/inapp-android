package ai.graine.inapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
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
        // The 0.3.0 constructor predates the chat and call tokens too: it builds a design without them.
        assertEquals(c.copy(c.getV(), c.getPreset(), c.getScheme(), c.getColors(), c.getFont(), c.getShape(), c.getSpacing(),
                c.getElevation(), c.getMotion(), c.getIcons(), c.getGlass(), c.getBubbles(), c.getChrome(), null, null), t);
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

    @Test public void aJavaAppHearsGrainesAnswersWithLambdas() {
        final String[] heard = {null, null};
        GraineCallback<GraineScheduledCall> booked = (call, error) -> {
            heard[0] = call == null ? null : call.getId();
            heard[1] = error == null ? null : error.getCode().getWire();
        };
        GraineScheduledCall call = new GraineScheduledCall("sc_1", new java.util.Date(1L));
        assertTrue(call.isUpcoming());
        assertTrue(call.getCanCancel());
        assertEquals(GraineCallStatus.SCHEDULED.INSTANCE, call.getStatus());
        booked.onResult(call, null);
        assertEquals("sc_1", heard[0]);
        booked.onResult(null, new GraineError(GraineErrorCode.TOO_SOON.INSTANCE, "Pick a later time."));
        assertEquals("too_soon", heard[1]);
        assertEquals(GraineErrorCode.NETWORK.INSTANCE, GraineErrorCode.from("network"));
        GraineSchedulingRules rules = new GraineSchedulingRules();
        assertEquals(false, rules.getUsersCanBook());
        assertEquals(5L * 60_000L, rules.earliest(new java.util.Date(0L)).getTime());
        GraineCallback<java.util.List<GraineMemory>> remembered = (memories, error) ->
                heard[1] = memories == null ? null : memories.get(0).getText();
        remembered.onResult(Collections.singletonList(new GraineMemory("m1", "Prefers calls after 6 pm")), null);
        assertEquals("Prefers calls after 6 pm", heard[1]);
        GraineCallback<Integer> forgot = (deleted, error) -> heard[0] = deleted == null ? null : "deleted " + deleted;
        forgot.onResult(3, null);
        assertEquals("deleted 3", heard[0]);
    }

    @Test public void aJavaAppDrawsItsOwnChatWithOnlyWhatItMustWrite() {
        // typing, failed and shown are default methods: a Java source writes the five that matter.
        ChatSource source = new ChatSource() {
            @Override public java.util.List<GraineChatTurn> getMessages() {
                return Collections.singletonList(new GraineChatTurn("t1", Role.AGENT, "Hello", new java.util.Date()));
            }
            @Override public boolean getOnline() { return true; }
            @Override public void send(String text) {}
            @Override public void addChangeListener(Runnable listener) {}
            @Override public void removeChangeListener(Runnable listener) {}
        };
        assertEquals("Hello", source.getMessages().get(0).getText());
        assertEquals(false, source.getTyping());
        assertEquals(false, source.getFailed());
        source.shown(true);
    }

    @Test public void theCallScreensWordsBuildWithoutTheOptionalOnes() {
        CallStatusText words = new CallStatusText("Incoming call", "Connecting…", "Call ended");
        assertEquals("Scheduled call", words.getScheduledText());
        assertEquals("Missed call", words.getMissedText());
        GraineCall hosted = new GraineCall("ic_1", GraineCall.Direction.INCOMING, "Acme", null,
                Collections.<String, String>emptyMap(), true);
        assertTrue(hosted.getHosted());
        assertEquals("ai.graine.inapp.action.CALL_BACK", GraineCalls.ACTION_CALL_BACK);
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

    @SuppressWarnings({"ConstantConditions", "unused"})
    @Test public void aJavaAppNamesItsOtherAgentsAndPicksOnePerCall() {
        assertEquals("default", GraineInApp.DEFAULT);
        assertEquals("ai.graine.inapp.extra.AGENT", GraineCalls.EXTRA_AGENT);
        assertNull(GraineInApp.find("nobody"));
        // Compiled, never run (they need an Application and an activity): the overloads a Java app writes.
        if (System.nanoTime() < 0) {
            GraineInApp support = GraineInApp.init(null, new GraineConfig("https://www.graine.ai", "pk_live_support"));
            GraineInApp sales = GraineInApp.init(null, new GraineConfig("https://www.graine.ai", "pk_live_sales"), "sales");
            GraineInApp again = GraineInApp.get();
            GraineInApp named = GraineInApp.get("sales");
            java.util.Map<String, GraineInApp> all = GraineInApp.getAll();
            String name = sales.getName();
            sales.getCalls().startCall(null, "upgrade", Collections.<String, Object>emptyMap(), "sales");
            sales.getCalls().startCall(null, "help");
            GraineInApp with = sales.getCalls().getCallAgent();
            GraineChatActivity.open(null);
            GraineChatActivity.open(null, "sales");
        }
    }

    @Test public void aJavaAppLaysOutAChatOfItsOwn() {
        java.util.Date at = new java.util.Date(1_760_000_000_000L);
        GraineChatTurn hi = new GraineChatTurn("t1", Role.AGENT, "Hi", at);
        java.util.List<ChatRows.Row> rows = ChatRows.build(Collections.singletonList(hi), at, java.util.TimeZone.getTimeZone("UTC"));
        assertEquals(1, rows.size());
        assertTrue(rows.get(0) instanceof ChatRows.Row.Turn);
        assertEquals("Voice call · 6:05 PM", ChatRows.callLabel("6:05 PM"));
        assertTrue(ChatRows.offersStarters(Collections.singletonList(hi)));
        assertTrue(!ChatRows.offersNewConversation(Collections.singletonList(hi)));
    }

    @SuppressWarnings({"ConstantConditions", "unused"})
    @Test public void aJavaAppOffersTheBookingSheet() {
        // Compiled, never run (they need an Application and an activity): the calls a Java app writes.
        if (System.nanoTime() < 0) {
            GraineInApp graine = GraineInApp.get();
            boolean offer = graine.getBookingAvailable();
            graine.presentBooking(null, (call, error) -> { });
            graine.presentBooking(null, "Card replacement", (call, error) -> { });
            new GraineBookingDialog(null).show();
            new GraineBookingDialog(null, graine, "Card replacement", (call, error) -> { }).show();
        }
    }
}
