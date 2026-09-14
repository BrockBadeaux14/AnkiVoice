package org.ankivoice.av005;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One operator-driven scenario in the AV-005 suite.
 *
 * Every scenario needs a person: the suite speaks a VoiceQA prompt, waits a recorded
 * settling interval, opens the microphone, and then requires the operator to attest what
 * they actually did before the turn is accepted. Nothing here touches an Anki collection
 * and no scenario ever produces a rating.
 */
final class Scenario {

    /** What the operator has to do while the turn is running. */
    enum Action {
        SPEAK_ANSWER,
        STAY_SILENT,
        USE_REPEAT,
        USE_CANCEL,
        EXTERNAL_SETUP
    }

    final String id;
    final String title;
    final String instructions;
    final int[] turns;
    final int settleMs;
    final int thinkingMs;
    final int completeSilenceMs;
    final int possiblyCompleteSilenceMs;
    final int minimumMs;
    final int watchdogMs;
    final Action action;
    /** Cancel capture from the app this many ms after it opens; 0 disables. */
    final int autoCancelMs;
    /** Start a second overlapping recognition to provoke ERROR_RECOGNIZER_BUSY. */
    final boolean secondRecognizer;
    /** Open capture while the prompt is still audible, to test prompt echo. */
    final boolean captureDuringPlayback;

    private Scenario(Builder builder) {
        this.id = builder.id;
        this.title = builder.title;
        this.instructions = builder.instructions;
        this.turns = builder.turns;
        this.settleMs = builder.settleMs;
        this.thinkingMs = builder.thinkingMs;
        this.completeSilenceMs = builder.completeSilenceMs;
        this.possiblyCompleteSilenceMs = builder.possiblyCompleteSilenceMs;
        this.minimumMs = builder.minimumMs;
        this.watchdogMs = builder.watchdogMs;
        this.action = builder.action;
        this.autoCancelMs = builder.autoCancelMs;
        this.secondRecognizer = builder.secondRecognizer;
        this.captureDuringPlayback = builder.captureDuringPlayback;
    }

    static final class Builder {
        private final String id;
        private final String title;
        private String instructions = "";
        private int[] turns = {0};
        private int settleMs = 400;
        private int thinkingMs = 0;
        private int completeSilenceMs = 1200;
        private int possiblyCompleteSilenceMs = 900;
        private int minimumMs = 1000;
        private int watchdogMs = 30000;
        private Action action = Action.SPEAK_ANSWER;
        private int autoCancelMs = 0;
        private boolean secondRecognizer;
        private boolean captureDuringPlayback;

        Builder(String id, String title) {
            this.id = id;
            this.title = title;
        }

        Builder instructions(String value) { instructions = value; return this; }
        Builder turns(int... value) { turns = value; return this; }
        Builder settle(int value) { settleMs = value; return this; }
        Builder thinking(int value) { thinkingMs = value; return this; }
        Builder silence(int complete, int possibly) {
            completeSilenceMs = complete;
            possiblyCompleteSilenceMs = possibly;
            return this;
        }
        Builder minimum(int value) { minimumMs = value; return this; }
        Builder watchdog(int value) { watchdogMs = value; return this; }
        Builder action(Action value) { action = value; return this; }
        Builder autoCancel(int value) { autoCancelMs = value; return this; }
        Builder secondRecognizer() { secondRecognizer = true; return this; }
        Builder captureDuringPlayback() { captureDuringPlayback = true; return this; }

        Scenario build() { return new Scenario(this); }
    }

    private static int[] range(int from, int toExclusive) {
        int[] values = new int[toExclusive - from];
        for (int i = 0; i < values.length; i++) values[i] = from + i;
        return values;
    }

    /**
     * The fixed AV-005 matrix. Order matters: the twelve-turn loop runs first so the
     * headline timings are measured before any deliberate failure is provoked.
     */
    static List<Scenario> matrix() {
        List<Scenario> all = new ArrayList<>();

        all.add(new Builder("env", "1. Environment check")
                .instructions("No speech needed. Records the engine, voice, recognizer service, "
                        + "locale and microphone permission for the report. Tap Start.")
                .turns()
                .action(Action.EXTERNAL_SETUP)
                .build());

        all.add(new Builder("loop", "2. Twelve-turn foreground loop")
                .instructions("The core run: four VoiceQA prompts for three rounds. For each turn, "
                        + "listen to the spoken prompt, wait for SPEAK NOW, then read the answer "
                        + "shown on screen out loud at a normal pace. Attest each turn afterwards.")
                .turns(range(0, 12))
                .thinking(0)
                .build());

        all.add(new Builder("pause2", "3. Two-second thinking pause")
                .instructions("Same as the loop, but wait about two seconds after SPEAK NOW before "
                        + "you start speaking. This measures whether the recognizer ends the turn "
                        + "while you are still thinking.")
                .turns(0, 1, 2, 3)
                .thinking(2000)
                .build());

        all.add(new Builder("pause5", "4. Five-second thinking pause")
                .instructions("Same again, but wait about five seconds before speaking. Watch for a "
                        + "no-speech timeout firing before you get a chance to answer.")
                .turns(0, 1, 2, 3)
                .thinking(5000)
                .build());

        all.add(new Builder("silence", "5. No-speech timeout")
                .instructions("Say nothing at all. Stay quiet until the turn ends by itself. This "
                        + "records the real no-speech timeout and the error it reports.")
                .turns(0, 1)
                .action(Action.STAY_SILENT)
                .watchdog(45000)
                .build());

        all.add(new Builder("echo", "6. Prompt echo guard")
                .instructions("Say nothing. Capture deliberately opens while the prompt is still "
                        + "being spoken, to check whether the prompt audio can be transcribed as if "
                        + "it were your answer.")
                .turns(0, 1)
                .action(Action.STAY_SILENT)
                .captureDuringPlayback()
                .build());

        all.add(new Builder("repeat", "7. Repeat the prompt")
                .instructions("When SPEAK NOW appears, tap REPEAT instead of answering. The prompt "
                        + "should be spoken again and a fresh capture should open. Answer the second "
                        + "time.")
                .turns(0, 1)
                .action(Action.USE_REPEAT)
                .build());

        all.add(new Builder("cancel", "8. Cancel the turn")
                .instructions("Start speaking, then tap CANCEL while you are still talking. Capture "
                        + "must stop and no transcript may be recorded for the turn.")
                .turns(0, 1)
                .action(Action.USE_CANCEL)
                .build());

        all.add(new Builder("late_callback", "9. Cancellation with a late callback")
                .instructions("Speak normally. The app cancels capture 900 ms after it opens, while "
                        + "you are mid-answer. Any recognizer callback that arrives afterwards must "
                        + "be recorded as stale and must not advance the turn.")
                .turns(0, 1)
                .autoCancel(900)
                .build());

        all.add(new Builder("busy", "10. Recognizer busy")
                .instructions("Speak normally. The app deliberately starts a second recognition on "
                        + "top of the first to provoke a busy error. The error must stay distinct "
                        + "from a transcript.")
                .turns(0)
                .secondRecognizer()
                .build());

        all.add(new Builder("unavailable", "11. Recognizer unavailable")
                .instructions("BEFORE tapping Start: point the system at a recognition service "
                        + "that does not exist, so the recognizer cannot be created:\n"
                        + "adb shell settings put secure voice_recognition_service org.ankivoice.av005/.Missing\n"
                        + "Say nothing. Restore it afterwards with 'settings delete secure "
                        + "voice_recognition_service' and reboot the emulator.")
                .turns(0)
                .action(Action.EXTERNAL_SETUP)
                .build());

        all.add(new Builder("permission", "12. Microphone permission denied")
                .instructions("BEFORE tapping Start: revoke the microphone permission for this app "
                        + "(long-press the app icon, App info, Permissions, Microphone, Don't allow). "
                        + "Return here and tap Start. Say nothing. Re-grant the permission afterwards.")
                .turns(0)
                .action(Action.EXTERNAL_SETUP)
                .build());

        all.add(new Builder("network", "13. Network unavailable")
                .instructions("BEFORE tapping Start: turn on Airplane mode in the emulator. Return "
                        + "here, tap Start and speak the answer normally. Turn Airplane mode off "
                        + "again afterwards.")
                .turns(0)
                .action(Action.EXTERNAL_SETUP)
                .watchdog(45000)
                .build());

        all.add(new Builder("background", "14. Background during playback and capture")
                .instructions("Tap Start, then press Home while the prompt is still being spoken. "
                        + "Reopen the app from Recents. Repeat on the second turn, but press Home "
                        + "after SPEAK NOW appears. Playback and capture must stop safely.")
                .turns(0, 1)
                .action(Action.EXTERNAL_SETUP)
                .watchdog(60000)
                .build());

        all.add(new Builder("lock", "15. Screen lock during a turn")
                .instructions("Tap Start, then lock the screen (power button) while the turn is "
                        + "running. Unlock and reopen the app explicitly. Capture must not continue "
                        + "behind the lock screen and stale callbacks must not advance a turn.")
                .turns(0)
                .action(Action.EXTERNAL_SETUP)
                .watchdog(60000)
                .build());

        all.add(new Builder("focus", "16. Audio focus interruption")
                .instructions("Tap Start, then interrupt the audio: place an emulated call from "
                        + "Extended controls, or start playback in another app. Record what happens "
                        + "to prompt playback and capture.")
                .turns(0)
                .action(Action.EXTERNAL_SETUP)
                .watchdog(60000)
                .build());

        return all;
    }

    @Override public String toString() {
        return title + "  [" + turns.length + (turns.length == 1 ? " turn]" : " turns]");
    }

    List<Integer> turnList() {
        List<Integer> values = new ArrayList<>();
        for (int turn : turns) values.add(turn);
        return values;
    }

    @Override public boolean equals(Object other) {
        return other instanceof Scenario && ((Scenario) other).id.equals(id);
    }

    @Override public int hashCode() { return Arrays.hashCode(new Object[]{id}); }
}
