package org.ankivoice.av006;

import android.app.Activity;
import android.content.Intent;
import android.content.ComponentName;
import android.content.pm.PackageInfo;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.os.ParcelFileDescriptor;
import android.media.AudioFormat;
import android.speech.*;
import android.speech.tts.*;
import android.widget.TextView;
import org.json.*;
import java.io.*;
import java.util.*;

/** Disposable synthetic provider experiment. No Anki or cloud credentials. */
public class ProbeActivity extends Activity {
    private final Handler main = new Handler();
    private final JSONObject result = new JSONObject();
    private final JSONArray attempts = new JSONArray();
    private final String engine = "com.google.android.tts";
    private final String recognizerClass = "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService";
    private TextToSpeech tts;
    private SpeechRecognizer recognizer;
    private MediaPlayer player;
    private JSONObject corpus;
    private JSONObject current;
    private TextView status;
    private int index = 0;
    private long started;
    private Runnable timeout;
    private boolean finished;
    private volatile long audioEnded;
    private long speechEnded;
    private ParcelFileDescriptor[] pipe;
    private final ArrayList<String> segments = new ArrayList<>();

    private void put(JSONObject object, String key, Object value) {
        try { object.put(key, value == null ? JSONObject.NULL : value); }
        catch (JSONException e) { throw new IllegalStateException(e); }
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        status = new TextView(this); status.setText("AV006 synthetic provider probe"); setContentView(status);
        try {
            if (!Build.FINGERPRINT.contains("generic") && !Build.FINGERPRINT.contains("emu64"))
                throw new IllegalStateException("Emulator required");
            if (new File(getFilesDir(), "result.json").exists()) {
                finished = true;
                status.setText("Preserve/remove the previous result before another run"); return;
            }
            corpus = new JSONObject(read(getAssets().open("av006-corpus.json")));
            put(result, "fingerprint", Build.FINGERPRINT);
            put(result, "api", Build.VERSION.SDK_INT);
            put(result, "started_epoch_ms", System.currentTimeMillis());
            put(result, "engine", engine);
            PackageInfo info = getPackageManager().getPackageInfo(engine, 0);
            put(result, "engine_version", info.versionName);
            put(result, "engine_version_code", info.getLongVersionCode());
            put(result, "recognition_service", engine + "/" + recognizerClass);
            put(result, "recognition_available", SpeechRecognizer.isRecognitionAvailable(this));
            put(result, "on_device_recognition_available", SpeechRecognizer.isOnDeviceRecognitionAvailable(this));
            put(result, "attempts", attempts);
            String operation = getIntent().getStringExtra("op");
            put(result, "operation", operation);
            put(result, "prefer_offline", getIntent().getBooleanExtra("prefer_offline", true));
            if ("support".equals(operation)) { support(); return; }
            if ("download".equals(operation)) { download(); return; }
            if ("stt".equals(operation)) {
                index = getIntent().getIntExtra("start_index", 0);
                int end = getIntent().getIntExtra("end_index", 12);
                if (index < 0 || index >= end || end > 12) throw new IllegalArgumentException("Invalid corpus range");
                put(result, "start_index", index); put(result, "end_index", end);
                nextRecognition(); return;
            }
            if (!"tts".equals(operation)) throw new IllegalArgumentException("Use tts, support or stt");
            armTimeout(15000, () -> finishResult("tts_init_timeout"));
            tts = new TextToSpeech(this, code -> main.post(() -> initializeTts(code)), engine);
        } catch (Exception error) { finishResult(error.toString()); }
    }

    private static String read(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096]; int count;
        try (InputStream stream = input) {
            while ((count = stream.read(buffer)) != -1) output.write(buffer, 0, count);
        }
        return output.toString("UTF-8");
    }

    private void armTimeout(long milliseconds, Runnable callback) {
        if (timeout != null) main.removeCallbacks(timeout);
        timeout = callback; main.postDelayed(timeout, milliseconds);
    }

    private void initializeTts(int code) {
        if (finished) return;
        main.removeCallbacks(timeout);
        put(result, "tts_init_status", code);
        if (code != TextToSpeech.SUCCESS) { finishResult("tts_init_failed"); return; }
        put(result, "language_availability", tts.isLanguageAvailable(Locale.US));
        JSONArray voices = new JSONArray();
        List<Voice> local = new ArrayList<>();
        Set<Voice> available = tts.getVoices();
        if (available != null) for (Voice voice : available) {
            if (voice.getLocale().equals(Locale.US)) {
                JSONObject item = new JSONObject();
                put(item, "name", voice.getName()); put(item, "network_required", voice.isNetworkConnectionRequired());
                put(item, "features", new JSONArray(voice.getFeatures())); voices.put(item);
                if (!voice.isNetworkConnectionRequired() && !voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) local.add(voice);
            }
        }
        put(result, "en_us_voices", voices);
        local.sort(Comparator.comparing(Voice::getName));
        if (local.isEmpty()) { finishResult("no_installed_local_en_us_voice"); return; }
        Voice selected = local.get(0);
        put(result, "selected_voice", selected.getName());
        put(result, "set_voice_status", tts.setVoice(selected));
        put(result, "set_rate_status", tts.setSpeechRate(1.0f));
        put(result, "set_pitch_status", tts.setPitch(1.0f));
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { main.post(() -> synthesized(id)); }
            @Override public void onError(String id) { onError(id, -1); }
            @Override public void onError(String id, int code) { main.post(() -> {
                if (finished || current == null || !id.equals(current.optString("id"))) return;
                put(current, "error_code", code); put(current, "status", "synthesis_error"); next();
            }); }
        });
        next();
    }

    private void next() {
        if (timeout != null) main.removeCallbacks(timeout);
        try {
            JSONArray examples = corpus.getJSONArray("examples"), cases = corpus.getJSONArray("cases");
            if (index >= examples.length() + cases.length()) { finishResult(null); return; }
            boolean prompt = index < examples.length();
            JSONObject source = prompt ? examples.getJSONObject(index) : cases.getJSONObject(index - examples.length());
            current = new JSONObject();
            String id = (prompt ? "prompt-" : "answer-") + source.getString("id");
            String text = prompt ? source.getJSONObject("fields").getString("Prompt") : source.getString("answer");
            put(current, "id", id); put(current, "kind", prompt ? "prompt" : "answer");
            put(current, "text", text); put(current, "status", "started"); attempts.put(current); index++;
            status.setText("Synthesizing " + id); started = SystemClock.elapsedRealtime();
            armTimeout(30000, () -> {
                tts.stop(); put(current, "status", "synthesis_timeout"); finishResult("tts_timeout_stop");
            });
            int code = tts.synthesizeToFile(text, new Bundle(), new File(getFilesDir(), id + ".wav"), id);
            put(current, "enqueue_status", code);
            if (code != TextToSpeech.SUCCESS) { put(current, "status", "enqueue_error"); next(); }
        } catch (Exception error) { finishResult(error.toString()); }
    }

    private void synthesized(String id) {
        if (finished || !id.equals(current.optString("id"))) return;
        main.removeCallbacks(timeout);
        put(current, "synthesis_ms", SystemClock.elapsedRealtime() - started);
        put(current, "status", "synthesized");
        if (!"prompt".equals(current.optString("kind"))) { next(); return; }
        try {
            player = new MediaPlayer(); player.setDataSource(new File(getFilesDir(), id + ".wav").getPath());
            player.prepare(); put(current, "duration_ms", player.getDuration());
            long playbackStart = SystemClock.elapsedRealtime();
            player.setOnCompletionListener(value -> {
                put(current, "playback_ms", SystemClock.elapsedRealtime() - playbackStart);
                put(current, "status", "playback_completed"); player.release(); player = null; next();
            });
            player.setOnErrorListener((value, what, extra) -> {
                put(current, "status", "playback_error"); put(current, "playback_error", what);
                player.release(); player = null; next(); return true;
            });
            armTimeout(30000, () -> { put(current, "status", "playback_timeout"); finishResult("playback_timeout"); });
            player.start();
        } catch (Exception error) { put(current, "status", "playback_exception"); put(current, "error", error.toString()); next(); }
    }

    private void support() {
        recognizer = SpeechRecognizer.createSpeechRecognizer(this, new ComponentName(engine, recognizerClass));
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        armTimeout(15000, () -> finishResult("recognition_support_timeout"));
        recognizer.checkRecognitionSupport(intent, getMainExecutor(), new RecognitionSupportCallback() {
            @Override public void onSupportResult(RecognitionSupport value) {
                put(result, "installed_on_device_languages", new JSONArray(value.getInstalledOnDeviceLanguages()));
                put(result, "pending_on_device_languages", new JSONArray(value.getPendingOnDeviceLanguages()));
                put(result, "supported_on_device_languages", new JSONArray(value.getSupportedOnDeviceLanguages()));
                put(result, "online_languages", new JSONArray(value.getOnlineLanguages())); finishResult(null);
            }
            @Override public void onError(int code) { put(result, "recognition_support_error", code); finishResult("recognition_support_error"); }
        });
    }

    private void nextRecognition() {
        if (finished) return;
        try {
            JSONArray cases = corpus.getJSONArray("cases");
            if (index >= getIntent().getIntExtra("end_index", cases.length())) { finishResult(null); return; }
            JSONObject item = cases.getJSONObject(index++);
            String id = item.getString("id");
            // RIFF PCM generated by the pinned native TTS; parse chunks, not a fixed 44-byte header.
            byte[] pcm = null; int sampleRate = 0;
            try (RandomAccessFile wav = new RandomAccessFile(new File(getFilesDir(), "answer-" + id + ".wav"), "r")) {
                if (wav.readInt() != 0x52494646) throw new IOException("Not RIFF");
                wav.skipBytes(4);
                if (wav.readInt() != 0x57415645) throw new IOException("Not WAVE");
                while (wav.getFilePointer() + 8 <= wav.length()) {
                    int tag = wav.readInt(), length = Integer.reverseBytes(wav.readInt());
                    if (length < 0 || wav.getFilePointer() + length > wav.length()) throw new IOException("Invalid WAV chunk");
                    long end = wav.getFilePointer() + length + (length % 2);
                    if (tag == 0x666d7420) {
                        int format = Short.reverseBytes(wav.readShort()), channels = Short.reverseBytes(wav.readShort());
                        sampleRate = Integer.reverseBytes(wav.readInt()); wav.skipBytes(6);
                        int bits = Short.reverseBytes(wav.readShort());
                        if (format != 1 || channels != 1 || bits != 16) throw new IOException("Expected mono PCM16");
                    } else if (tag == 0x64617461) { pcm = new byte[length]; wav.readFully(pcm); }
                    wav.seek(end);
                }
            }
            if (pcm == null || sampleRate <= 0 || pcm.length > sampleRate * 2 * 30) throw new IOException("Invalid/overlong audio");
            current = new JSONObject(); put(current, "id", id); put(current, "expected_text", item.getString("answer"));
            put(current, "audio_seconds", pcm.length / (sampleRate * 2.0)); put(current, "sample_rate", sampleRate);
            put(current, "status", "started"); attempts.put(current); segments.clear(); speechEnded = 0; audioEnded = 0;
            started = SystemClock.elapsedRealtime(); status.setText("Recognizing " + id);
            recognizer = SpeechRecognizer.createSpeechRecognizer(this, new ComponentName(engine, recognizerClass));
            recognizer.setRecognitionListener(new RecognitionListener() {
                public void onReadyForSpeech(Bundle params) { put(current, "ready_ms", SystemClock.elapsedRealtime() - started); }
                public void onBeginningOfSpeech() { }
                public void onRmsChanged(float value) { }
                public void onBufferReceived(byte[] buffer) { }
                public void onEndOfSpeech() { speechEnded = SystemClock.elapsedRealtime(); }
                public void onError(int code) { completeRecognition(null, code); }
                public void onPartialResults(Bundle value) { }
                public void onEvent(int type, Bundle params) { }
                public void onResults(Bundle value) {
                    ArrayList<String> texts = value.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    completeRecognition(texts == null || texts.isEmpty() ? "" : texts.get(0), null);
                }
                public void onSegmentResults(Bundle value) {
                    ArrayList<String> texts = value.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (texts != null && !texts.isEmpty()) segments.add(texts.get(0));
                }
                public void onEndOfSegmentedSession() { completeRecognition(String.join(" ", segments), null); }
            });
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, getIntent().getBooleanExtra("prefer_offline", true));
            pipe = ParcelFileDescriptor.createPipe();
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0]);
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1);
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, sampleRate);
            intent.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE);
            armTimeout(45000, () -> completeRecognition(null, -100));
            recognizer.startListening(intent);
            final byte[] audio = pcm; final int chunk = sampleRate * 2 / 50;
            final ParcelFileDescriptor writer = pipe[1];
            new Thread(() -> {
                try (OutputStream stream = new ParcelFileDescriptor.AutoCloseOutputStream(writer)) {
                    for (int offset = 0; offset < audio.length; offset += chunk) {
                        stream.write(audio, offset, Math.min(chunk, audio.length - offset));
                        SystemClock.sleep(20);
                    }
                    audioEnded = SystemClock.elapsedRealtime();
                } catch (IOException error) { /* The result callback records provider errors/timeouts. */ }
            }, "av006-synthetic-audio").start();
        } catch (Exception error) { finishResult(error.toString()); }
    }

    private void download() {
        recognizer = SpeechRecognizer.createSpeechRecognizer(this, new ComponentName(engine, recognizerClass));
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        armTimeout(55000, () -> finishResult("model_download_timeout"));
        recognizer.triggerModelDownload(intent, getMainExecutor(), new ModelDownloadListener() {
            public void onProgress(int percent) { put(result, "download_progress_percent", percent); }
            public void onSuccess() { put(result, "download_status", "success"); finishResult(null); }
            public void onScheduled() { put(result, "download_status", "scheduled"); finishResult(null); }
            public void onError(int error) { put(result, "download_error", error); finishResult("model_download_error"); }
        });
    }

    private void completeRecognition(String transcript, Integer error) {
        if (finished || current == null || !"started".equals(current.optString("status"))) return;
        main.removeCallbacks(timeout);
        long now = SystemClock.elapsedRealtime();
        put(current, "total_ms", now - started);
        put(current, "audio_eof_ms", audioEnded > 0 ? audioEnded - started : null);
        put(current, "end_of_speech_ms", speechEnded > 0 ? speechEnded - started : null);
        put(current, "finalization_after_eof_ms", audioEnded > 0 ? now - audioEnded : null);
        put(current, "transcript", transcript); put(current, "error_code", error);
        put(current, "status", error != null ? "error" : transcript.isEmpty() ? "empty" : "success");
        recognizer.cancel(); recognizer.destroy(); recognizer = null;
        if (pipe != null) for (ParcelFileDescriptor descriptor : pipe) {
            try { descriptor.close(); } catch (IOException ignored) { }
        }
        if (error != null && error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            finishResult("native_stt_error_stop"); return;
        }
        main.postDelayed(() -> nextRecognition(), 300);
    }

    private void finishResult(String error) {
        if (finished) return; finished = true;
        if (timeout != null) main.removeCallbacks(timeout);
        put(result, "error", error); put(result, "finished_epoch_ms", System.currentTimeMillis());
        try (FileOutputStream output = new FileOutputStream(new File(getFilesDir(), "result.json"))) {
            output.write(result.toString(2).getBytes("UTF-8"));
        } catch (Exception exception) { status.setText(exception.toString()); return; }
        status.setText(error == null ? "AV006 measurement complete" : "AV006: " + error);
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (recognizer != null) recognizer.destroy();
        if (player != null) { player.release(); player = null; }
    }
    @Override public void onDestroy() {
        if (!finished) finishResult("activity_destroyed"); super.onDestroy();
    }
}
