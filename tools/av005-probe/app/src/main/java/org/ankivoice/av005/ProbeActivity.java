package org.ankivoice.av005;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * AV-005 foreground speech suite.
 *
 * An operator-driven probe: it speaks a VoiceQA prompt through Android text to speech,
 * waits a recorded settling interval, opens the live microphone, and requires the person
 * running it to attest what they actually did before a turn is accepted. It never reads
 * or writes an Anki collection and never produces a rating.
 */
public class ProbeActivity extends Activity implements RecognitionListener {

    static final String TAG = "AV005";
    static final String ENGINE = "com.google.android.tts";
    private static final int PERMISSION_REQUEST = 41;
    /**
     * Audio focus is observed, never acted on.
     *
     * Both halves of a turn take audio focus for themselves: the Google text-to-speech
     * engine takes it for each utterance, and SpeechRecognizer takes it the moment
     * capture opens. An app that also holds its own focus request therefore receives a
     * transient loss caused by its own work, at unpredictable length (34 ms to over
     * 1.3 s on this image). Cancelling a turn on that signal makes the loop cancel
     * itself, which is exactly what happened in this investigation before the rule
     * below was adopted. Real interruptions are detected through the activity
     * lifecycle (onPause, which a call or lock screen triggers) and through the
     * recognizer's own errors. The focus listener only records.
     */

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Scenario> scenarios = Scenario.matrix();
    private final JSONArray completedScenarios = new JSONArray();

    private TextToSpeech tts;
    private SpeechRecognizer recognizer;
    private SpeechRecognizer secondRecognizer;
    private AudioManager audio;
    private AudioFocusRequest focusRequest;

    private JSONArray corpus;
    private JSONObject environment;
    private String operator = "human";

    private Spinner picker;
    private TextView modeView;
    private TextView instructions;
    private TextView state;
    private TextView progressView;
    private TextView promptView;
    private TextView heardView;
    private TextView timingView;
    private ProgressBar level;
    private Button start;
    private Button repeat;
    private Button cancel;
    private Button spoke;
    private Button silent;
    private Button interrupted;
    private Button save;
    private TextView logView;
    private ScrollView logScroll;

    /* Per-run state. */
    private Scenario scenario;
    private JSONObject run;
    private JSONArray turns;
    private JSONArray lifecycle;
    private JSONArray stale;
    private JSONObject turn;
    private List<Integer> queue;
    private int position;
    private int token;
    private long base;
    private boolean foreground;
    private boolean listening;
    private boolean speaking;
    private boolean awaitingAttestation;
    private boolean running;
    private Runnable watchdog;
    private Runnable pendingStart;
    private long focusLostAtMs = -1;

    // ------------------------------------------------------------------ setup

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(buildLayout());
        base = SystemClock.elapsedRealtime();
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        try {
            corpus = new JSONObject(read(getAssets().open("av005-turns.json"))).getJSONArray("turns");
        } catch (Exception error) {
            state.setText("Cannot read the turn corpus: " + error);
            start.setEnabled(false);
            return;
        }
        /* The permission-denied scenario needs the permission to stay denied, so the
           request is skipped when that scenario is the one selected. */
        boolean testingDenial = "permission".equals(getIntent().getStringExtra("scenario"));
        if (!testingDenial
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST);
        }
        /* An investigator driving the suite over adb must never be recorded as a person
           speaking into the microphone. The label travels with every scenario and the
           validator refuses to count an investigator turn as live-speech evidence. */
        String claimed = getIntent().getStringExtra("operator");
        if ("investigator_adb".equals(claimed)) operator = "investigator_adb";
        selectScenarioFromIntent(getIntent());
        tts = new TextToSpeech(this, code -> main.post(() -> onTtsReady(code)), ENGINE);
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(this);
        modeView.setText("investigator_adb".equals(operator)
                ? "MODE: investigator over adb \u2014 no human voice; not live-speech evidence"
                : "MODE: human operator \u2014 speak each answer yourself");
        modeView.setTextColor(android.graphics.Color.parseColor(
                "investigator_adb".equals(operator) ? "#8a5a00" : "#1b3a7f"));
        log("AV-005 suite ready. Pick a scenario and read its instructions before tapping Start.");
        log("Operated by: " + operator);
        log("Evidence is written to " + new File(getExternalFilesDir(null), "").getAbsolutePath());
    }

    /**
     * Honours an optional {@code scenario} extra so an Android Studio run configuration can
     * open straight onto one scenario. It only preselects: a person still has to tap Start.
     */
    private void selectScenarioFromIntent(Intent intent) {
        String wanted = intent == null ? null : intent.getStringExtra("scenario");
        if (wanted == null) return;
        for (int i = 0; i < scenarios.size(); i++) {
            if (scenarios.get(i).id.equals(wanted)) {
                picker.setSelection(i);
                instructions.setText(scenarios.get(i).instructions);
                log("Preselected scenario \"" + wanted + "\" from the launch intent.");
                return;
            }
        }
        log("Unknown scenario extra \"" + wanted + "\"; leaving the default selection.");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!running) selectScenarioFromIntent(intent);
    }

    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 24, 32, 24);
        /* Edge to edge on API 35+: keep the controls clear of the status and gesture bars. */
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars());
            view.setPadding(32, 24 + bars.top, 32, 24 + bars.bottom);
            return insets;
        });

        TextView title = text("AV-005 foreground speech suite", 20, true);
        root.addView(title);
        root.addView(text("Operator-driven. Every turn needs a person: listen, speak, attest.", 13, false));
        modeView = text("", 13, true);
        root.addView(modeView);

        picker = new Spinner(this);
        ArrayAdapter<Scenario> adapter =
                new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, scenarios);
        picker.setAdapter(adapter);
        picker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int index, long id) {
                if (!running) instructions.setText(scenarios.get(index).instructions);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        root.addView(picker);

        instructions = text(scenarios.get(0).instructions, 14, false);
        instructions.setPadding(0, 16, 0, 16);
        root.addView(instructions);

        LinearLayout controls = row();
        start = button("Start", v -> startScenario());
        cancel = button("CANCEL", v -> operatorCancel());
        repeat = button("REPEAT", v -> operatorRepeat());
        controls.addView(start);
        controls.addView(repeat);
        controls.addView(cancel);
        root.addView(controls);

        state = text("Idle", 22, true);
        state.setPadding(0, 24, 0, 8);
        root.addView(state);

        level = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        level.setMax(100);
        root.addView(level);

        progressView = text("", 13, true);
        progressView.setPadding(0, 12, 0, 0);
        root.addView(progressView);

        promptView = text("", 16, false);
        promptView.setPadding(0, 8, 0, 0);
        root.addView(promptView);

        heardView = text("", 16, true);
        heardView.setPadding(0, 12, 0, 0);
        root.addView(heardView);

        timingView = text("", 12, false);
        timingView.setPadding(0, 8, 0, 8);
        root.addView(timingView);

        LinearLayout attest = row();
        spoke = button("I spoke it", v -> attest("spoke_answer"));
        silent = button("I stayed silent", v -> attest("stayed_silent"));
        interrupted = button("Interrupted", v -> attest("interrupted"));
        attest.addView(spoke);
        attest.addView(silent);
        attest.addView(interrupted);
        root.addView(attest);

        save = button("Save evidence", v -> saveEvidence());
        save.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(save);

        logScroll = new ScrollView(this);
        logView = text("", 11, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logScroll.addView(logView);
        root.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setBusy(false);
        return root;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.START);
        return layout;
    }

    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button view = new Button(this);
        view.setText(label);
        view.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        view.setLayoutParams(params);
        return view;
    }

    private void setBusy(boolean busy) {
        start.setEnabled(!busy && !awaitingAttestation);
        picker.setEnabled(!busy && !awaitingAttestation);
        repeat.setEnabled(busy);
        cancel.setEnabled(busy);
        spoke.setEnabled(awaitingAttestation);
        silent.setEnabled(awaitingAttestation);
        interrupted.setEnabled(awaitingAttestation);
        save.setEnabled(!busy);
    }

    // ------------------------------------------------------------ environment

    private void onTtsReady(int code) {
        if (code != TextToSpeech.SUCCESS) {
            log("Text to speech failed to initialise: status " + code);
            state.setText("TTS unavailable");
            return;
        }
        tts.setLanguage(Locale.US);
        tts.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build());
        Voice chosen = null;
        Set<Voice> available = tts.getVoices();
        int localCount = 0;
        if (available != null) {
            for (Voice voice : available) {
                boolean usable = Locale.US.equals(voice.getLocale())
                        && !voice.isNetworkConnectionRequired()
                        && !voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED);
                if (!usable) continue;
                localCount++;
                if (chosen == null || voice.getName().compareTo(chosen.getName()) < 0) chosen = voice;
            }
        }
        if (chosen != null) tts.setVoice(chosen);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { main.post(() -> playbackStarted(id)); }
            @Override public void onDone(String id) { main.post(() -> playbackDone(id, false)); }
            @Override public void onError(String id) { main.post(() -> playbackDone(id, true)); }
            @Override public void onStop(String id, boolean interruptedFlag) {
                main.post(() -> note("tts_stopped"));
            }
        });
        environment = describeEnvironment(localCount);
        log("Engine " + environment.optString("engine_version")
                + ", voice " + environment.optJSONObject("voice").optString("name")
                + ", recognition available " + environment.optBoolean("recognition_available"));
        state.setText("Ready");
    }

    private JSONObject describeEnvironment(int localVoiceCount) {
        JSONObject record = new JSONObject();
        put(record, "fingerprint", Build.FINGERPRINT);
        put(record, "api", Build.VERSION.SDK_INT);
        put(record, "device", Build.DEVICE);
        put(record, "product", Build.PRODUCT);
        put(record, "locale", "en-US");
        put(record, "engine", ENGINE);
        try {
            PackageInfo info = getPackageManager().getPackageInfo(ENGINE, 0);
            put(record, "engine_version", info.versionName);
            put(record, "engine_version_code", info.getLongVersionCode());
        } catch (PackageManager.NameNotFoundException error) {
            put(record, "engine_version", null);
        }
        put(record, "recognition_available", SpeechRecognizer.isRecognitionAvailable(this));
        put(record, "on_device_recognition_available", SpeechRecognizer.isOnDeviceRecognitionAvailable(this));
        put(record, "record_audio_granted",
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED);
        put(record, "tts_language_availability", tts == null ? null : tts.isLanguageAvailable(Locale.US));
        JSONArray services = new JSONArray();
        Intent query = new Intent("android.speech.RecognitionService");
        for (android.content.pm.ResolveInfo info :
                getPackageManager().queryIntentServices(query, PackageManager.MATCH_ALL)) {
            services.put(info.serviceInfo.packageName + "/" + info.serviceInfo.name);
        }
        put(record, "recognition_services", services);
        JSONObject voice = new JSONObject();
        Voice active = tts == null ? null : tts.getVoice();
        put(voice, "name", active == null ? null : active.getName());
        put(voice, "network_required", active != null && active.isNetworkConnectionRequired());
        put(voice, "local_en_us_voice_count", localVoiceCount);
        put(record, "voice", voice);
        put(record, "audio_mode", audio == null ? null : audio.getMode());
        /* Network state matters: the recognizer on this image may use a remote service,
           so the report must be able to show what connectivity was during each run. */
        put(record, "airplane_mode_on",
                android.provider.Settings.Global.getInt(getContentResolver(),
                        android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) == 1);
        android.net.ConnectivityManager connectivity =
                (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        android.net.NetworkCapabilities capabilities = connectivity == null ? null
                : connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
        put(record, "has_validated_internet", capabilities != null
                && capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED));
        return record;
    }

    // ------------------------------------------------------------- scenario

    private void startScenario() {
        scenario = scenarios.get(picker.getSelectedItemPosition());
        if (tts == null || environment == null) {
            toast("Text to speech is not ready yet");
            return;
        }
        run = new JSONObject();
        turns = new JSONArray();
        lifecycle = new JSONArray();
        stale = new JSONArray();
        queue = scenario.turnList();
        position = 0;
        running = true;
        base = SystemClock.elapsedRealtime();
        put(run, "schema_version", 1);
        put(run, "scenario", scenario.id);
        put(run, "title", scenario.title);
        put(run, "operator_action", scenario.action.name());
        put(run, "operated_by", operator);
        put(run, "voice_source", "investigator_adb".equals(operator) ? "none" : "human_microphone");
        put(run, "started_epoch_ms", System.currentTimeMillis());
        put(run, "environment", describeEnvironment(
                environment.optJSONObject("voice").optInt("local_en_us_voice_count")));
        JSONObject requested = new JSONObject();
        put(requested, "settle_ms", scenario.settleMs);
        put(requested, "operator_thinking_ms", scenario.thinkingMs);
        put(requested, "complete_silence_ms", scenario.completeSilenceMs);
        put(requested, "possibly_complete_silence_ms", scenario.possiblyCompleteSilenceMs);
        put(requested, "minimum_length_ms", scenario.minimumMs);
        put(requested, "watchdog_ms", scenario.watchdogMs);
        put(requested, "auto_cancel_ms", scenario.autoCancelMs);
        put(requested, "second_recognizer", scenario.secondRecognizer);
        put(requested, "capture_during_playback", scenario.captureDuringPlayback);
        put(run, "requested_settings", requested);
        put(run, "turns", turns);
        put(run, "lifecycle_events", lifecycle);
        put(run, "stale_callbacks", stale);

        log("--- " + scenario.title + " (" + queue.size() + " turns)");
        requestFocus();
        setBusy(true);
        if (queue.isEmpty()) {
            finishScenario();
            return;
        }
        nextTurn();
    }

    private void nextTurn() {
        if (!running) return;
        if (position >= queue.size()) {
            finishScenario();
            return;
        }
        try {
            int index = queue.get(position);
            JSONObject source = corpus.getJSONObject(index);
            token++;
            turn = new JSONObject();
            put(turn, "turn_index", index);
            put(turn, "position", position);
            put(turn, "round", source.getInt("round"));
            put(turn, "example_id", source.getString("example_id"));
            put(turn, "prompt", source.getString("prompt"));
            put(turn, "expected_answer", source.getString("expected_answer"));
            put(turn, "settle_ms", scenario.settleMs);
            put(turn, "operator_thinking_ms", scenario.thinkingMs);
            put(turn, "partials", new JSONArray());
            put(turn, "alternatives", new JSONArray());
            put(turn, "rms_peak", 0.0);
            put(turn, "repeats", 0);
            put(turn, "manual_intervention", scenario.action != Scenario.Action.SPEAK_ANSWER);
            put(turn, "status", "pending");
            put(turn, "transcript", null);
            put(turn, "error_code", null);
            put(turn, "error_name", null);
            for (String key : new String[]{"playback_start_ms", "playback_done_ms", "listen_requested_ms",
                    "ready_for_speech_ms", "beginning_of_speech_ms", "end_of_speech_ms", "final_ms"}) {
                put(turn, key, null);
            }
            turns.put(turn);

            progressView.setText(String.format(Locale.US, "Turn %d of %d  \u00b7  round %d  \u00b7  %s",
                    position + 1, queue.size(), source.getInt("round"), source.getString("example_id")));
            promptView.setText("Prompt: " + source.getString("prompt"));
            heardView.setText("");
            timingView.setText("");
            level.setProgress(0);
            state.setText("Listen to the prompt…");
            speakPrompt(source.getString("prompt"));
        } catch (JSONException error) {
            log("Corpus error: " + error);
            finishScenario();
        }
    }

    private void speakPrompt(String prompt) {
        Bundle params = new Bundle();
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC);
        put(turn, "tts_request_ms", now());
        speaking = true;
        mark("playback_request");
        int queued = tts.speak(prompt, TextToSpeech.QUEUE_FLUSH, params, utteranceId());
        put(turn, "tts_speak_result", queued);
        if (queued != TextToSpeech.SUCCESS) {
            completeTurn("error", "tts_speak_rejected");
            return;
        }
        if (scenario.captureDuringPlayback) {
            put(turn, "echo_experiment", true);
            startListening();
        }
        armWatchdog(scenario.watchdogMs, "turn_watchdog");
    }

    private String utteranceId() {
        return "turn-" + token;
    }

    private void playbackStarted(String id) {
        if (!running || turn == null || !utteranceId().equals(id)) return;
        put(turn, "playback_start_ms", now());
        mark("playback_start");
    }

    private void playbackDone(String id, boolean failed) {
        if (!running || turn == null || !utteranceId().equals(id)) return;
        speaking = false;
        put(turn, "playback_done_ms", now());
        put(turn, "playback_failed", failed);
        mark("playback_done");
        if (failed) {
            completeTurn("error", "tts_playback_error");
            return;
        }
        if (scenario.captureDuringPlayback) return;
        if (!foreground) {
            note("playback_done_while_not_foreground");
            completeTurn("halted", "left_foreground_before_capture");
            return;
        }
        state.setText("…");
        final int expected = token;
        pendingStart = () -> {
            pendingStart = null;
            if (!running || expected != token) return;
            startListening();
        };
        main.postDelayed(pendingStart, scenario.settleMs);
    }

    private void startListening() {
        if (!running || turn == null) return;
        if (!foreground) {
            note("capture_suppressed_not_foreground");
            completeTurn("halted", "left_foreground_before_capture");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            put(turn, "permission_at_capture", false);
        } else {
            put(turn, "permission_at_capture", true);
        }
        put(turn, "listen_requested_ms", now());
        listening = true;
        recognizer.startListening(recognitionIntent());
        mark("listen_requested");
        state.setText("SPEAK NOW");
        state.setTextColor(Color.parseColor("#1b7f3b"));
        if (scenario.action == Scenario.Action.SPEAK_ANSWER
                || scenario.action == Scenario.Action.USE_CANCEL) {
            heardView.setText("Read aloud: " + turn.optString("expected_answer"));
        }
        if (scenario.action == Scenario.Action.STAY_SILENT) state.setText("STAY SILENT");
        if (scenario.action == Scenario.Action.USE_REPEAT) state.setText("TAP REPEAT");
        if (scenario.action == Scenario.Action.USE_CANCEL) state.setText("SPEAK, THEN TAP CANCEL");
        if (scenario.thinkingMs > 0) {
            state.setText("WAIT " + (scenario.thinkingMs / 1000) + "s, THEN SPEAK");
        }
        if (scenario.autoCancelMs > 0) {
            final int expected = token;
            main.postDelayed(() -> {
                if (!running || expected != token || !listening) return;
                note("app_cancelled_mid_capture");
                listening = false;
                recognizer.cancel();
                put(turn, "app_cancelled_ms", now());
                main.postDelayed(() -> {
                    if (running && token == expected && turn != null) {
                        completeTurn("cancelled", "app_cancel_auto");
                    }
                }, 4000);
            }, scenario.autoCancelMs);
        }
        if (scenario.secondRecognizer) {
            final int expected = token;
            main.postDelayed(() -> {
                if (!running || expected != token) return;
                note("second_recognition_started");
                if (secondRecognizer == null) {
                    secondRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
                }
                secondRecognizer.setRecognitionListener(new SecondListener());
                secondRecognizer.startListening(recognitionIntent());
            }, 300);
        }
    }

    private Intent recognitionIntent() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                scenario.completeSilenceMs);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                scenario.possiblyCompleteSilenceMs);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, scenario.minimumMs);
        return intent;
    }

    // ------------------------------------------------------- operator actions

    private void operatorRepeat() {
        if (!running || turn == null) return;
        note("operator_repeat");
        put(turn, "repeats", turn.optInt("repeats") + 1);
        put(turn, "manual_intervention", true);
        if (listening) {
            listening = false;
            recognizer.cancel();
        }
        main.removeCallbacks(pendingStart);
        try {
            speakPrompt(corpus.getJSONObject(queue.get(position)).getString("prompt"));
        } catch (JSONException error) {
            completeTurn("error", "repeat_failed");
        }
    }

    private void operatorCancel() {
        if (!running) return;
        note("operator_cancel");
        if (turn != null) put(turn, "manual_intervention", true);
        stopAudioWork("operator_cancel");
        if (turn != null) {
            put(turn, "final_ms", now());
            completeTurn("cancelled", "operator_cancel");
        } else {
            finishScenario();
        }
    }

    private void attest(String attestation) {
        if (!awaitingAttestation) return;
        JSONObject last = turns.optJSONObject(turns.length() - 1);
        if (last != null) {
            put(last, "operator_attestation", attestation);
            put(last, "attested_epoch_ms", System.currentTimeMillis());
        }
        awaitingAttestation = false;
        log("  attested: " + attestation);
        position++;
        setBusy(true);
        main.postDelayed(this::nextTurn, 600);
    }

    // ------------------------------------------------------- turn completion

    private void completeTurn(String outcome, String detail) {
        if (!running || turn == null) return;
        clearWatchdog();
        if (listening) {
            listening = false;
            recognizer.cancel();
        }
        put(turn, "status", outcome);
        if (detail != null) put(turn, "detail", detail);
        Long listenAt = optLong(turn, "listen_requested_ms");
        Long finalAt = optLong(turn, "final_ms");
        Long endOfSpeech = optLong(turn, "end_of_speech_ms");
        Long playbackStart = optLong(turn, "playback_start_ms");
        Long playbackDone = optLong(turn, "playback_done_ms");
        if (listenAt != null && finalAt != null) put(turn, "capture_ms", finalAt - listenAt);
        if (endOfSpeech != null && finalAt != null) {
            put(turn, "finalization_after_eos_ms", finalAt - endOfSpeech);
        }
        if (playbackStart != null && playbackDone != null) {
            put(turn, "playback_ms", playbackDone - playbackStart);
        }
        if (playbackDone != null && listenAt != null) {
            put(turn, "measured_settle_ms", listenAt - playbackDone);
        }
        put(turn, "echo_suspected", echoSuspected(turn));
        mark("turn_done");

        String heard = turn.optString("transcript", null);
        heardView.setText(heard == null || "null".equals(heard)
                ? "Heard: (no transcript) " + turn.optString("error_name", outcome)
                : "Heard: " + heard);
        timingView.setText(summarise(turn));
        log(String.format(Locale.US, "  turn %s %s %s",
                turn.opt("turn_index"), outcome,
                heard == null || "null".equals(heard) ? turn.optString("error_name", "") : "\"" + heard + "\""));

        turn = null;
        awaitingAttestation = true;
        state.setText("Attest what you did");
        state.setTextColor(Color.parseColor("#8a5a00"));
        setBusy(false);
    }

    private String summarise(JSONObject record) {
        StringBuilder builder = new StringBuilder();
        append(builder, record, "playback_ms", "playback");
        append(builder, record, "measured_settle_ms", "settle");
        append(builder, record, "beginning_of_speech_ms", "speech@");
        append(builder, record, "end_of_speech_ms", "eos@");
        append(builder, record, "capture_ms", "capture");
        append(builder, record, "finalization_after_eos_ms", "final-after-eos");
        builder.append("rms ").append(record.optDouble("rms_peak", 0));
        return builder.toString();
    }

    private void append(StringBuilder builder, JSONObject record, String key, String label) {
        Long value = optLong(record, key);
        if (value != null) builder.append(label).append(' ').append(value).append("ms  ");
    }

    private void finishScenario() {
        running = false;
        clearWatchdog();
        stopAudioWork("scenario_end");
        abandonFocus();
        put(run, "finished_epoch_ms", System.currentTimeMillis());
        put(run, "duration_ms", now());
        put(run, "completed_turns", turns.length());
        completedScenarios.put(run);
        progressView.setText("");
        state.setText("Scenario complete");
        state.setTextColor(Color.parseColor("#1b3a7f"));
        log("--- " + scenario.title + " complete (" + turns.length() + " turns). Tap Save evidence.");
        setBusy(false);
        saveEvidence();
    }

    // -------------------------------------------------- recognition callbacks

    private boolean rejectStale(String callback) {
        String reason = null;
        if (!running) reason = "scenario_not_running";
        else if (turn == null) reason = "no_active_turn";
        else if (!foreground) reason = "activity_not_foreground";
        else if (!listening) reason = "capture_already_closed";
        if (reason == null) return false;
        JSONObject entry = new JSONObject();
        put(entry, "callback", callback);
        put(entry, "t_ms", now());
        put(entry, "reason", reason);
        put(entry, "advanced_turn", false);
        if (stale != null) stale.put(entry);
        mark("stale_callback:" + callback + ":" + reason);
        log("  stale callback ignored: " + callback + " (" + reason + ")");
        return true;
    }

    @Override public void onReadyForSpeech(Bundle params) {
        if (rejectStale("onReadyForSpeech")) return;
        put(turn, "ready_for_speech_ms", now());
        mark("capture_ready");
    }

    @Override public void onBeginningOfSpeech() {
        if (rejectStale("onBeginningOfSpeech")) return;
        if (optLong(turn, "beginning_of_speech_ms") == null) put(turn, "beginning_of_speech_ms", now());
        mark("speech_begin");
    }

    @Override public void onRmsChanged(float rms) {
        level.setProgress(Math.max(0, Math.min(100, (int) ((rms + 2f) * 10f))));
        if (turn == null) return;
        if (rms > turn.optDouble("rms_peak", 0.0)) {
            put(turn, "rms_peak", Math.round(rms * 100.0) / 100.0);
        }
    }

    @Override public void onBufferReceived(byte[] buffer) { }

    @Override public void onEndOfSpeech() {
        if (rejectStale("onEndOfSpeech")) return;
        put(turn, "end_of_speech_ms", now());
        mark("speech_end");
        state.setText("Finalising…");
    }

    @Override public void onPartialResults(Bundle partial) {
        if (rejectStale("onPartialResults")) return;
        ArrayList<String> texts = partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (texts == null || texts.isEmpty()) return;
        JSONObject entry = new JSONObject();
        put(entry, "t_ms", now());
        put(entry, "text", texts.get(0));
        turn.optJSONArray("partials").put(entry);
        heardView.setText("Hearing: " + texts.get(0));
    }

    @Override public void onEvent(int type, Bundle params) { }

    @Override public void onResults(Bundle results) {
        if (rejectStale("onResults")) return;
        listening = false;
        put(turn, "final_ms", now());
        ArrayList<String> texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (texts == null || texts.isEmpty()) {
            completeTurn("error", "empty_results_bundle");
            return;
        }
        put(turn, "transcript", texts.get(0));
        JSONArray alternatives = turn.optJSONArray("alternatives");
        for (int i = 1; i < texts.size(); i++) alternatives.put(texts.get(i));
        completeTurn("success", null);
    }

    @Override public void onError(int code) {
        if (rejectStale("onError:" + errorName(code))) return;
        listening = false;
        put(turn, "final_ms", now());
        put(turn, "error_code", code);
        put(turn, "error_name", errorName(code));
        /* An error is never a transcript: the transcript field stays null. */
        completeTurn("error", null);
    }

    /** Records the overlapping recognition used by the busy scenario. */
    private final class SecondListener implements RecognitionListener {
        private void record(String event, Integer code) {
            JSONObject entry = new JSONObject();
            put(entry, "event", event);
            put(entry, "t_ms", now());
            put(entry, "error_code", code);
            put(entry, "error_name", code == null ? null : errorName(code));
            if (run != null) {
                JSONArray second = run.optJSONArray("second_recognizer_events");
                if (second == null) {
                    second = new JSONArray();
                    put(run, "second_recognizer_events", second);
                }
                second.put(entry);
            }
            log("  second recognizer: " + event + (code == null ? "" : " " + errorName(code)));
        }
        @Override public void onReadyForSpeech(Bundle params) { record("onReadyForSpeech", null); }
        @Override public void onBeginningOfSpeech() { record("onBeginningOfSpeech", null); }
        @Override public void onRmsChanged(float rms) { }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { record("onEndOfSpeech", null); }
        @Override public void onError(int code) { record("onError", code); }
        @Override public void onResults(Bundle results) { record("onResults", null); }
        @Override public void onPartialResults(Bundle partial) { }
        @Override public void onEvent(int type, Bundle params) { }
    }

    // -------------------------------------------------------- audio lifecycle

    private void requestFocus() {
        if (audio == null || focusRequest != null) return;
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(this::onFocusChange, main)
                .build();
        put(run, "audio_focus_request", audio.requestAudioFocus(focusRequest));
    }

    private void onFocusChange(int change) {
        note("audio_focus_change_" + change);
        log("  audio focus change: " + change + " (recorded, not acted on)");
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            if (focusLostAtMs >= 0) {
                long blip = now() - focusLostAtMs;
                recordFocusBlip(blip);
                log("  audio focus regained after " + blip + "ms");
            }
            focusLostAtMs = -1;
            return;
        }
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            focusLostAtMs = now();
            note(speaking ? "audio_focus_loss_during_own_playback"
                    : listening ? "audio_focus_loss_during_capture" : "audio_focus_loss_while_idle");
        }
    }

    /** Keeps every transient focus loss in the evidence, whatever caused it. */
    private void recordFocusBlip(long milliseconds) {
        if (run == null || milliseconds < 0) return;
        JSONArray blips = run.optJSONArray("self_inflicted_focus_blips_ms");
        if (blips == null) {
            blips = new JSONArray();
            put(run, "self_inflicted_focus_blips_ms", blips);
        }
        blips.put(milliseconds);
    }

    private void abandonFocus() {
        if (audio != null && focusRequest != null) {
            audio.abandonAudioFocusRequest(focusRequest);
            focusRequest = null;
        }
    }

    /** Stops playback and capture without letting a late callback advance a turn. */
    private void stopAudioWork(String reason) {
        note("stop_audio_work_" + reason);
        if (tts != null && speaking) {
            tts.stop();
            speaking = false;
        }
        if (recognizer != null && listening) {
            listening = false;
            recognizer.cancel();
        }
        if (pendingStart != null) {
            main.removeCallbacks(pendingStart);
            pendingStart = null;
        }
        level.setProgress(0);
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        note("on_resume");
    }

    @Override protected void onPause() {
        super.onPause();
        foreground = false;
        note("on_pause");
        stopAudioWork("on_pause");
        if (running && turn != null) {
            put(turn, "final_ms", now());
            completeTurn("halted", "left_foreground");
            log("  turn halted because the app left the foreground");
        }
    }

    @Override protected void onDestroy() {
        note("on_destroy");
        if (recognizer != null) recognizer.destroy();
        if (secondRecognizer != null) secondRecognizer.destroy();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        abandonFocus();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == PERMISSION_REQUEST) {
            log("Microphone permission granted: "
                    + (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED));
        }
    }

    // ---------------------------------------------------------------- evidence

    private void saveEvidence() {
        if (completedScenarios.length() == 0) {
            toast("Nothing to save yet");
            return;
        }
        JSONObject document = new JSONObject();
        put(document, "schema_version", 1);
        put(document, "suite", "AV-005 foreground speech");
        put(document, "operator_driven", true);
        put(document, "operated_by", operator);
        put(document, "anki_touched", false);
        put(document, "rating_produced", false);
        put(document, "written_epoch_ms", System.currentTimeMillis());
        put(document, "environment", environment);
        put(document, "scenarios", completedScenarios);
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File target = new File(getExternalFilesDir(null), "av005-" + stamp + ".json");
        File staging = new File(getExternalFilesDir(null), "av005-" + stamp + ".json.part");
        /* Write and flush to a staging file, then rename, so a run that is killed
           mid-save leaves no truncated evidence behind. */
        try {
            try (FileOutputStream stream = new FileOutputStream(staging)) {
                stream.write(document.toString(2).getBytes("UTF-8"));
                stream.flush();
                stream.getFD().sync();
            }
            if (!staging.renameTo(target)) throw new IOException("could not rename " + staging);
            log("Saved " + target.getAbsolutePath());
            toast("Saved " + target.getName());
        } catch (Exception error) {
            staging.delete();
            log("Could not save evidence: " + error);
            toast("Save failed");
        }
    }

    // ----------------------------------------------------------------- helpers

    private long now() {
        return SystemClock.elapsedRealtime() - base;
    }

    private void note(String event) {
        if (lifecycle == null) return;
        JSONObject entry = new JSONObject();
        put(entry, "event", event);
        put(entry, "t_ms", now());
        put(entry, "speaking", speaking);
        put(entry, "listening", listening);
        put(entry, "turn_index", turn == null ? JSONObject.NULL : turn.opt("turn_index"));
        lifecycle.put(entry);
        mark("lifecycle:" + event);
    }

    private void mark(String event) {
        Log.i(TAG, "MARK scenario=" + (scenario == null ? "-" : scenario.id)
                + " event=" + event + " t=" + now());
    }

    private void log(String line) {
        logView.append(line + "\n");
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        Log.i(TAG, line);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void armWatchdog(long milliseconds, String reason) {
        clearWatchdog();
        final int expected = token;
        watchdog = () -> {
            if (!running || token != expected || turn == null) return;
            put(turn, "final_ms", now());
            put(turn, "watchdog_reason", reason);
            completeTurn("timeout", reason);
        };
        main.postDelayed(watchdog, milliseconds);
    }

    private void clearWatchdog() {
        if (watchdog != null) main.removeCallbacks(watchdog);
        watchdog = null;
    }

    /** True when the transcript looks like the spoken prompt rather than an answer. */
    private static boolean echoSuspected(JSONObject record) {
        String transcript = record.optString("transcript", "");
        String prompt = record.optString("prompt", "");
        if (transcript == null || transcript.isEmpty() || "null".equals(transcript)) return false;
        String heard = transcript.toLowerCase(Locale.US).replaceAll("[^a-z0-9 ]", "").trim();
        String spoken = prompt.toLowerCase(Locale.US).replaceAll("[^a-z0-9 ]", "").trim();
        if (heard.length() < 8) return false;
        return spoken.contains(heard) || heard.contains(spoken);
    }

    private static Long optLong(JSONObject object, String key) {
        Object value = object == null ? null : object.opt(key);
        return value instanceof Number ? ((Number) value).longValue() : null;
    }

    private static void put(JSONObject object, String key, Object value) {
        try {
            object.put(key, value == null ? JSONObject.NULL : value);
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String read(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        try (InputStream stream = input) {
            while ((count = stream.read(buffer)) != -1) output.write(buffer, 0, count);
        }
        return output.toString("UTF-8");
    }

    static String errorName(int code) {
        switch (code) {
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "ERROR_NETWORK_TIMEOUT";
            case SpeechRecognizer.ERROR_NETWORK: return "ERROR_NETWORK";
            case SpeechRecognizer.ERROR_AUDIO: return "ERROR_AUDIO";
            case SpeechRecognizer.ERROR_SERVER: return "ERROR_SERVER";
            case SpeechRecognizer.ERROR_CLIENT: return "ERROR_CLIENT";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "ERROR_SPEECH_TIMEOUT";
            case SpeechRecognizer.ERROR_NO_MATCH: return "ERROR_NO_MATCH";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "ERROR_RECOGNIZER_BUSY";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "ERROR_INSUFFICIENT_PERMISSIONS";
            case SpeechRecognizer.ERROR_TOO_MANY_REQUESTS: return "ERROR_TOO_MANY_REQUESTS";
            case SpeechRecognizer.ERROR_SERVER_DISCONNECTED: return "ERROR_SERVER_DISCONNECTED";
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED: return "ERROR_LANGUAGE_NOT_SUPPORTED";
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE: return "ERROR_LANGUAGE_UNAVAILABLE";
            case SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT: return "ERROR_CANNOT_CHECK_SUPPORT";
            default: return "ERROR_UNKNOWN_" + code;
        }
    }
}
