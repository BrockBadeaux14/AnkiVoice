package org.ankivoice.av042;

/** No Android service, microphone, turns or operator attestations. */
public final class GuardChecks {
    static int checks;
    static void check(boolean condition) { checks++; if (!condition) throw new AssertionError("check " + checks); }
    public static void main(String[] args) {
        TrialGate g = new TrialGate();
        long old = g.begin(); g.capture(100);
        check(!g.expired(15099)); check(g.expired(15100));
        check(!g.result(old, "premature"));
        g.finish(15100); check(!g.expired(20099)); check(g.expired(20100));
        g.close(); long fresh = g.begin(); g.capture(21000); g.finish(22000);
        check(!g.result(old, "old final")); check(g.transcript == null);
        check(g.accepts(fresh, TrialGate.Phase.FINALIZING));
        check(g.result(fresh, "current final")); check("current final".equals(g.transcript));
        check(!g.result(fresh, "duplicate"));
        for (TrialGate.Phase phase : new TrialGate.Phase[]{TrialGate.Phase.PLAYBACK, TrialGate.Phase.CAPTURE, TrialGate.Phase.FINALIZING}) {
            long token = g.begin(); g.phase = phase; g.close();
            check(!g.accepts(token, phase)); check(!g.result(token, "late after interruption"));
        }
        check(!TrialGate.reserveAllowed(-1)); check(TrialGate.reserveAllowed(0));
        check(TrialGate.reserveAllowed(29)); check(!TrialGate.reserveAllowed(30));
        System.out.println("{\"checks\":" + checks + ",\"passed\":true,\"audio_turns\":0}");
    }
}
