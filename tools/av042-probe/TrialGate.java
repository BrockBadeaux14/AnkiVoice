package org.ankivoice.av042;

/** Disposable monotonic policy shared by the probe and zero-audio checks. */
public final class TrialGate {
    public enum Phase { IDLE, PLAYBACK, THINKING, CAPTURE, FINALIZING, CLOSED }
    public static final long CAPTURE_MS = 15000, FINAL_MS = 5000, SETTLE_MS = 400;
    public static final int TURN_CAP = 30;
    public long generation, deadline;
    public Phase phase = Phase.IDLE;
    public String transcript;
    public long begin() { generation++; phase = Phase.PLAYBACK; transcript = null; return generation; }
    public boolean accepts(long token, Phase expected) { return token == generation && phase == expected; }
    public void capture(long now) { phase = Phase.CAPTURE; deadline = now + CAPTURE_MS; }
    public void finish(long now) { phase = Phase.FINALIZING; deadline = now + FINAL_MS; }
    public boolean expired(long now) { return (phase == Phase.CAPTURE || phase == Phase.FINALIZING) && now >= deadline; }
    public boolean result(long token, String text) {
        if (!accepts(token, Phase.FINALIZING)) return false;
        transcript = text; close(); return true;
    }
    public void close() { generation++; phase = Phase.CLOSED; }
    public static boolean reserveAllowed(int used) { return used >= 0 && used < TURN_CAP; }
}
