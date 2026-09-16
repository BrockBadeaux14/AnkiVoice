package org.ankivoice.av042;

import android.app.Activity;
import android.content.*;
import android.media.*;
import android.os.*;
import android.speech.*;
import android.speech.tts.*;
import android.util.AtomicFile;
import android.util.Log;
import android.view.WindowManager;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Disposable AV-042 probe. No Anki access, grading, or provider credentials. */
public final class ProbeActivity extends Activity {
    static final String ENGINE = "com.google.android.tts";
    static final String SERVICE = "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService";
    final Handler main = new Handler(Looper.getMainLooper());
    final TrialGate gate = new TrialGate();
    JSONObject ledger, turn, example;
    JSONArray events;
    AtomicFile evidence;
    AudioManager audio;
    TextToSpeech tts;
    MediaPlayer player;
    SpeechRecognizer recognizer;
    AudioRecord recorder;
    AudioFocusRequest focus;
    ParcelFileDescriptor[] pipe;
    Thread captureThread;
    final AtomicBoolean recording = new AtomicBoolean(false);
    AudioManager.AudioRecordingCallback recordingCallback;
    AudioManager.OnModeChangedListener modeListener;
    AudioDeviceCallback deviceCallback;
    BroadcastReceiver receiver;
    TextView status, details, level;
    Button start, answer, done, audible, spoke, silent;
    String caseName;
    int corpusIndex;
    boolean initialized, foreground, ownCleanup, ready, firstFrames;
    long thinkingUntil, started, lastLevel;
    final ArrayList<String> segments = new ArrayList<>();
    StringBuilder trace = new StringBuilder();

    static void put(JSONObject obj, String key, Object val) {
        try { obj.put(key, val == null ? JSONObject.NULL : val); }
        catch (JSONException e) { throw new IllegalStateException(e); }
    }
    static JSONObject obj(Object... args) {
        JSONObject v = new JSONObject(); for (int i=0;i<args.length;i+=2) put(v,(String)args[i],args[i+1]); return v;
    }
    long now() { return SystemClock.elapsedRealtime(); }
    boolean active() { return gate.phase != TrialGate.Phase.IDLE && gate.phase != TrialGate.Phase.CLOSED; }
    void persist() {
        FileOutputStream out = null;
        try { out = evidence.startWrite(); out.write(ledger.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8)); evidence.finishWrite(out); }
        catch (Exception e) { if (out != null) evidence.failWrite(out); throw new IllegalStateException("Evidence persistence failed",e); }
    }
    void event(String type, Object value) {
        if (turn == null) return;
        events.put(obj("elapsed_ms",now()-started,"event",type,"value",value,"phase",gate.phase.name()));
        Log.i("AV042", "attempt="+turn.optInt("id")+" event="+type+" value="+value+" phase="+gate.phase);
        persist();
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        audio = getSystemService(AudioManager.class);
        evidence = new AtomicFile(new File(getFilesDir(),"ledger.json"));
        try {
            if (evidence.getBaseFile().exists()) {
                try(InputStream in=evidence.openRead()) { ledger=new JSONObject(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)); }
                JSONArray turns=ledger.getJSONArray("turns");
                for(int i=0;i<turns.length();i++) {
                    JSONObject old=turns.getJSONObject(i);
                    if("reserved".equals(old.optString("status"))) put(old,"status","process_or_activity_ended");
                }
            } else ledger=obj("schema_version",1,"issue",51,"attempt_cap",30,"turns",new JSONArray());
            caseName=getIntent().getStringExtra("case"); if(caseName==null) caseName="spoken";
            corpusIndex=getIntent().getIntExtra("index",0);
            try(InputStream in=getAssets().open("av005-turns.json")) {
                example=new JSONObject(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getJSONArray("turns").getJSONObject(corpusIndex);
            }
            put(ledger,"fingerprint",Build.FINGERPRINT); put(ledger,"api",Build.VERSION.SDK_INT);
            put(ledger,"engine",ENGINE); put(ledger,"service",SERVICE);
            put(ledger,"engine_version",getPackageManager().getPackageInfo(ENGINE,0).versionName);
            put(ledger,"capture_approach","AudioRecord_MIC_PCM16_16k_to_segmented_recognizer_pipe");
            put(ledger,"permission_design","RECORD_AUDIO only"); persist();
            buildUi(); monitors();
            tts=new TextToSpeech(this,code->main.post(()->initTts(code)),ENGINE);
        } catch(Exception e) { TextView error=new TextView(this); error.setText(e.toString()); setContentView(error); Log.e("AV042","startup",e); }
    }
    Button button(LinearLayout root,String label,Runnable action) {
        Button b=new Button(this);b.setText(label);root.addView(b);b.setOnClickListener(v->action.run());return b;
    }
    void buildUi() {
        ScrollView scroll=new ScrollView(this); LinearLayout root=new LinearLayout(this); root.setOrientation(1); root.setPadding(24,16,24,16);scroll.addView(root);setContentView(scroll);
        TextView title=new TextView(this);title.setText("AV-042 • "+caseName+" • example "+corpusIndex);title.setTextSize(23);root.addView(title);
        details=new TextView(this);details.setTextSize(18);root.addView(details);
        details.setText("Prompt: "+example.optString("prompt")+"\nExpected speech: "+example.optString("expected_answer")+"\nUsed turns: "+ledger.optJSONArray("turns").length()+"/30\nTap START, listen, then START ANSWER. Speak only when SPEAK NOW appears. DONE closes your audio. No automatic next turn.");
        status=new TextView(this);status.setTextSize(23);status.setText("Initializing pinned local voice…");root.addView(status);
        level=new TextView(this);root.addView(level);
        start=button(root,"START TRIAL",()->begin());
        answer=button(root,"START ANSWER",()->startCapture());
        done=button(root,"DONE — FINISH ANSWER",()->finishCapture("explicit_done"));
        button(root,"CANCEL / PAUSE",()->interrupt("explicit_cancel"));
        audible=button(root,"PROMPT WAS AUDIBLE",()->attest("prompt_audible",true));
        spoke=button(root,"I SPOKE THE DISPLAYED ANSWER",()->attest("operator_spoke_expected",true));
        silent=button(root,"I STAYED SILENT",()->attest("operator_stayed_silent",true));
        controls();
    }
    void controls() {
        if(start==null)return;
        start.setEnabled(initialized&&!active()&&foreground);
        answer.setEnabled(gate.phase==TrialGate.Phase.THINKING&&now()>=thinkingUntil);
        done.setEnabled(gate.phase==TrialGate.Phase.CAPTURE);
        boolean closed=turn!=null&&gate.phase==TrialGate.Phase.CLOSED;
        audible.setEnabled(closed);spoke.setEnabled(closed);silent.setEnabled(closed);
    }
    void attest(String key,boolean value) { if(turn==null||gate.phase!=TrialGate.Phase.CLOSED)return; put(turn,key,value);event("operator_attestation",key); status.setText(status.getText()+"\nRecorded: "+key); }
    void initTts(int code) {
        if(code!=TextToSpeech.SUCCESS){status.setText("TTS init failed");return;}
        Voice selected=null; for(Voice v:tts.getVoices()) if(v.getName().equals("en-US-language")&&!v.isNetworkConnectionRequired()) selected=v;
        if(selected==null){status.setText("Pinned voice absent");return;}
        tts.setVoice(selected);tts.setSpeechRate(1f);tts.setPitch(1f);put(ledger,"voice",selected.getName());persist();
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
            public void onStart(String id){}
            public void onDone(String id){main.post(()->play(Long.parseLong(id)));}
            public void onError(String id){main.post(()->{if(gate.accepts(Long.parseLong(id),TrialGate.Phase.PLAYBACK))end("tts_error",null);});}
        });
        initialized=true;status.setText("Ready. No recording until you start.");controls();
        if(getIntent().getBooleanExtra("auto",false))main.postDelayed(()->begin(),500);
    }
    void begin() {
        if(!initialized||active()||!foreground)return;
        if(audio.getMode()!=AudioManager.MODE_NORMAL){status.setText("Blocked: non-normal audio mode");return;}
        JSONArray turns=ledger.optJSONArray("turns");
        if(!TrialGate.reserveAllowed(turns.length())){status.setText("30-turn budget exhausted. STOP.");return;}
        started=now();long token=gate.begin(); segments.clear();ready=false;firstFrames=false;
        events=new JSONArray();turn=obj("id",turns.length()+1,"case",caseName,"corpus_index",corpusIndex,"expected_answer",example.optString("expected_answer"),"example_id",example.optString("example_id"),"started_epoch_ms",System.currentTimeMillis(),"status","reserved","events",events,"thinking_ms",getIntent().getIntExtra("thinking_ms",0),"capture_limit_ms",TrialGate.CAPTURE_MS,"finalization_limit_ms",TrialGate.FINAL_MS,"audio_source","live_microphone","automated",getIntent().getBooleanExtra("auto",false));
        put(turn,"prefer_offline",false);put(turn,"post_done_silence_ms",500);
        turns.put(turn);persist();event("reserved",token);
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener(change->{event("focus",change);if(change<0&&active())interrupt("audio_focus_loss");},main).build();
        int granted=audio.requestAudioFocus(focus);event("focus_request",granted);
        if(granted!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){end("focus_unavailable",null);return;}
        controls();status.setText("Preparing prompt…");
        if(caseName.equals("diagnostic")){gate.phase=TrialGate.Phase.THINKING;thinkingUntil=now();startCapture();return;}
        int queued=tts.synthesizeToFile(example.optString("prompt"),new Bundle(),new File(getFilesDir(),"prompt.wav"),Long.toString(token));
        if(queued!=TextToSpeech.SUCCESS){end("tts_enqueue_failed",null);return;}
        main.postDelayed(()->{if(gate.accepts(token,TrialGate.Phase.PLAYBACK))end("playback_timeout",null);},30000);
    }
    void play(long token) {
        if(!gate.accepts(token,TrialGate.Phase.PLAYBACK))return;
        try {
            player=new MediaPlayer();player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            player.setDataSource(new File(getFilesDir(),"prompt.wav").getPath());player.prepare();
            player.setOnCompletionListener(p->{if(!gate.accepts(token,TrialGate.Phase.PLAYBACK))return;event("playback_done",null);player.release();player=null;gate.phase=TrialGate.Phase.THINKING;
                thinkingUntil=now()+TrialGate.SETTLE_MS+getIntent().getIntExtra("thinking_ms",0);status.setText("Think first. START ANSWER will enable shortly.");controls();
                main.postDelayed(()->{if(gate.accepts(token,TrialGate.Phase.THINKING)){status.setText("Ready for START ANSWER");controls();if(getIntent().getBooleanExtra("auto",false)||caseName.equals("echo"))startCapture();}},thinkingUntil-now());});
            player.setOnErrorListener((p,a,b)->{if(gate.accepts(token,TrialGate.Phase.PLAYBACK))end("playback_error",null);return true;});
            player.start();event("playback_started",null);status.setText("LISTEN to the prompt");
        }catch(Exception e){event("playback_exception",e.toString());end("playback_error",null);}
    }
    void startCapture() {
        if(gate.phase!=TrialGate.Phase.THINKING||now()<thinkingUntil||!foreground)return;
        if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){end("microphone_permission_missing",null);return;}
        long token=gate.generation; gate.capture(now()); controls();status.setText("Opening microphone — wait for SPEAK NOW");event("capture_start",null);
        try {
            recorder=new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(Math.max(6400,AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)*2)).build();
            final AudioRecord rec=recorder;
            recordingCallback=new AudioManager.AudioRecordingCallback(){public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs){
                if(token!=gate.generation)return;
                for(AudioRecordingConfiguration config:configs){event("recording_configuration",obj("silenced",config.isClientSilenced(),"session",config.getClientAudioSessionId(),"source",config.getClientAudioSource()));if(config.isClientSilenced())interrupt("client_silenced");}
            }};
            recorder.registerAudioRecordingCallback(getMainExecutor(),recordingCallback);
            recorder.addOnRoutingChangedListener(r->{if(token==gate.generation){AudioDeviceInfo d=rec.getRoutedDevice();event("capture_route",d==null?null:obj("id",d.getId(),"type",d.getType(),"name",d.getProductName().toString()));if(d==null&&recording.get())interrupt("capture_route_lost");}},main);
            if(!caseName.equals("diagnostic")) {
                pipe=ParcelFileDescriptor.createPipe();
                recognizer=SpeechRecognizer.createSpeechRecognizer(this,new ComponentName(ENGINE,SERVICE));recognizer.setRecognitionListener(new Listener(token));
                Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"en-US");intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,false);intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE,pipe[0]);
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT,1);intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,AudioFormat.ENCODING_PCM_16BIT);
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE,16000);intent.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION,RecognizerIntent.EXTRA_AUDIO_SOURCE);
                recognizer.startListening(intent);event("recognizer_start",null);
            } else ready=true;
            recorder.startRecording();recording.set(true);event("recorder_started",obj("session",rec.getAudioSessionId(),"state",rec.getRecordingState()));
            ParcelFileDescriptor writer=pipe==null?null:pipe[1];
            captureThread=new Thread(()->pump(rec,writer,token),"av042-live-pcm");captureThread.start();
            Runnable deadline=new Runnable(){public void run(){if(token!=gate.generation)return;if(gate.expired(now())){
                if(gate.phase==TrialGate.Phase.CAPTURE){event("capture_deadline",null);finishCapture("capture_limit");}
                else {event("finalization_deadline",null);end("finalization_timeout",null);}
            }if(token==gate.generation&&active())main.postDelayed(this,50);}};main.postDelayed(deadline,50);
        }catch(Exception e){event("capture_exception",e.toString());end("capture_exception",null);}
    }
    void pump(AudioRecord rec,ParcelFileDescriptor writer,long token) {
        // Capture independently of recognition; only level summaries are persisted, never raw audio.
        try(OutputStream stream=writer==null?OutputStream.nullOutputStream():new ParcelFileDescriptor.AutoCloseOutputStream(writer);
            OutputStream diagnosticPcm=new FileOutputStream(new File(getFilesDir(),"last-input.pcm"))) {
            short[] samples=new short[320];long count=0,nonzero=0;double sum=0;int peak=0;long next=now()+250;
            while(recording.get()) {
                int n=rec.read(samples,0,samples.length,AudioRecord.READ_BLOCKING);if(n<0)throw new IOException("AudioRecord.read="+n);
                byte[] bytes=new byte[n*2];
                for(int i=0;i<n;i++){int s=samples[i];sum+=(double)s*s;peak=Math.max(peak,Math.abs(s));if(s!=0)nonzero++;count++;bytes[i*2]=(byte)s;bytes[i*2+1]=(byte)(s>>8);}
                if(!recording.get())break;
                diagnosticPcm.write(bytes);
                stream.write(bytes);
                if(now()>=next){final long frames=count,nz=nonzero;final double rms=count==0?0:Math.sqrt(sum/count);final int pk=peak;
                    main.post(()->{if(token!=gate.generation)return;firstFrames=true;event("pcm_level",obj("samples",frames,"nonzero",nz,"rms",rms,"peak",pk));level.setText("Microphone RMS "+Math.round(rms)+" • peak "+pk);if(ready&&gate.phase==TrialGate.Phase.CAPTURE)status.setText(caseName.equals("echo")?"STAY SILENT — echo check":"SPEAK NOW, then tap DONE");});
                    next=now()+250;count=0;nonzero=0;sum=0;peak=0;
                }
            }
            // Let the recognizer process the final word before EOF. This is generated silence,
            // not an injected answer; the diagnostic file contains microphone samples only.
            if(writer!=null) { byte[] silence=new byte[640]; for(int i=0;i<25;i++) { stream.write(silence); SystemClock.sleep(20); } }
        }catch(Exception e){main.post(()->{if(token==gate.generation&&recording.get()){event("pcm_error",e.toString());end("pcm_error",null);}});}
        finally { main.post(()->{if(token==gate.generation)event("audio_pipe_closed",null);}); }
    }
    final class Listener implements RecognitionListener {
        final long token;Listener(long t){token=t;}
        boolean valid(){return token==gate.generation;}
        public void onReadyForSpeech(Bundle b){if(valid()){ready=true;event("recognizer_ready",null);if(firstFrames)status.setText("SPEAK NOW, then tap DONE");}}
        public void onBeginningOfSpeech(){if(valid())event("speech_begin",null);}
        public void onRmsChanged(float f){}
        public void onBufferReceived(byte[] b){}
        public void onEndOfSpeech(){if(valid())event("speech_end",null);}
        public void onError(int e){if(valid()){event("recognizer_error",e);end("recognizer_error_"+e,null);}else event("stale_callback_rejected",obj("token",token,"error",e));}
        public void onPartialResults(Bundle b){if(valid())event("partial",texts(b));}
        public void onEvent(int t,Bundle b){}
        public void onSegmentResults(Bundle b){if(valid()){String text=texts(b);segments.add(text);event("segment",text);}}
        public void onEndOfSegmentedSession(){if(valid())finalResult(token,String.join(" ",segments));else event("stale_callback_rejected",token);}
        public void onResults(Bundle b){if(valid())finalResult(token,texts(b));else event("stale_callback_rejected",token);}
    }
    String texts(Bundle b){ArrayList<String> t=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);return t==null||t.isEmpty()?"":t.get(0);}
    void finalResult(long token,String text) {
        event("raw_final",text);
        if(!gate.accepts(token,TrialGate.Phase.FINALIZING)){end("recognizer_closed_before_finish",null);return;}
        if(gate.expired(now())){end("finalization_timeout",null);return;}
        end(text.isBlank()?"empty":"transcript",text);
    }
    void finishCapture(String reason) {
        if(gate.phase!=TrialGate.Phase.CAPTURE)return;
        gate.finish(now());event("finish_requested",reason);put(turn,"finish_ms",now()-started);controls();status.setText("Finalizing…");
        stopRecorder();
        if(caseName.equals("diagnostic"))end("diagnostic_complete",null);
    }
    void stopRecorder() {
        recording.set(false);
        if(recorder!=null){try{recorder.stop();}catch(Exception e){event("recorder_stop_error",e.toString());}}
        // Closing the write side ends the segmented session. Cancellation also closes the read side
        // to release a writer blocked in a full pipe. No main-thread join can freeze the deadline.
    }
    void end(String reason,String transcript) {
        if(!active())return;
        event("closing",reason);gate.close(); // Invalidate before every native cleanup callback.
        put(turn,"status",reason);put(turn,"transcript",transcript);put(turn,"ended_ms",now()-started);
        cleanup();status.setText("Stopped: "+reason+(transcript==null?"":"\nHeard: "+transcript)+"\nAttest only what happened. Wait before another trial.");controls();persist();
    }
    void cleanup() {
        ownCleanup=true;
        stopRecorder();
        if(recognizer!=null){recognizer.cancel();recognizer.destroy();recognizer=null;}
        if(pipe!=null){for(ParcelFileDescriptor p:pipe)try{p.close();}catch(IOException ignored){}pipe=null;}
        if(recorder!=null){if(recordingCallback!=null)recorder.unregisterAudioRecordingCallback(recordingCallback);recorder.release();recorder=null;}
        if(player!=null){player.release();player=null;}
        if(tts!=null)tts.stop();
        if(focus!=null){audio.abandonAudioFocusRequest(focus);focus=null;}
        event("cleanup_complete",obj("recording",recording.get(),"recorder_released",recorder==null,"recognizer_released",recognizer==null,"player_released",player==null,"pipe_closed",pipe==null));ownCleanup=false;
    }
    void interrupt(String reason){if(active()){event("interruption",reason);end("interrupted_"+reason,null);}}
    void monitors() {
        modeListener=mode->{event("audio_mode",mode);if(mode!=AudioManager.MODE_NORMAL)interrupt("audio_mode_"+mode);};audio.addOnModeChangedListener(getMainExecutor(),modeListener);
        deviceCallback=new AudioDeviceCallback(){public void onAudioDevicesRemoved(AudioDeviceInfo[] ds){if(ds.length>0){event("device_removed",ds.length);interrupt("audio_device_removed");}}};audio.registerAudioDeviceCallback(deviceCallback,main);
        receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){event("broadcast",i.getAction());interrupt(i.getAction());}};
        IntentFilter f=new IntentFilter(Intent.ACTION_SCREEN_OFF);f.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY);registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED);
    }
    @Override public void onResume(){super.onResume();foreground=true;event("onResume",null);controls();}
    @Override public void onPause(){foreground=false;event("onPause",null);interrupt("activity_pause");controls();super.onPause();}
    @Override public void onDestroy(){interrupt("activity_destroy");if(tts!=null)tts.shutdown();if(modeListener!=null)audio.removeOnModeChangedListener(modeListener);if(deviceCallback!=null)audio.unregisterAudioDeviceCallback(deviceCallback);if(receiver!=null)unregisterReceiver(receiver);super.onDestroy();}
}
