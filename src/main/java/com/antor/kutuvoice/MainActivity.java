package com.antor.kutuvoice;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

/**
 * KUTU Voice — the ear + mouth for KUTU (the assistant in Antor's Muse chat).
 *
 * The app itself has NO brain and NO network. It listens (Android's own
 * SpeechRecognizer), shows the transcript, and hands the words to KUTU via
 * Android's share sheet ("Send to KUTU"). Robot-flavoured spoken feedback
 * comes from the device TextToSpeech engine with a low pitch.
 *
 * Wake mode: while the switch is ON and the app is open, recognition runs in
 * a loop; hearing "kutu" / "কুটু" beeps + vibrates and the NEXT utterance is
 * captured as the command.
 */
public class MainActivity extends Activity implements RecognitionListener {

    // Listening states
    private static final int IDLE = 0;
    private static final int SINGLE = 1;        // mic-button one-shot capture
    private static final int WAKE_LISTEN = 2;   // wake-mode loop, waiting for the word
    private static final int WAKE_COMMAND = 3;  // wake word heard, capturing the command

    private static final int REQ_RECORD_AUDIO = 42;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView statusView;
    private EditText transcriptView;
    private RadioGroup langGroup;
    private Switch wakeSwitch;

    private SpeechRecognizer recognizer;
    private boolean recognizerBusy = false;
    private int state = IDLE;
    private boolean restartPending = false;

    private TextToSpeech tts;
    private boolean ttsReady = false;

    // ------------------------------------------------------------------ UI

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        initTts();
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("Ei phone e speech recognition nai");
            setStatus("Speech recognition unavailable");
        }
        ensureAudioPermission();
    }

    private void buildUi() {
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(13, 26, 19));

        TextView title = new TextView(this);
        title.setText("KUTU Voice");
        title.setTextSize(32);
        title.setTextColor(Color.rgb(84, 230, 144));
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView flavor = new TextView(this);
        flavor.setText("🦜🤖 Ami KUTU-r kaan ar mukh.\nBolo — ami sune KUTU ke pouche dibo.");
        flavor.setTextSize(15);
        flavor.setTextColor(Color.WHITE);
        flavor.setGravity(Gravity.CENTER);
        flavor.setPadding(0, dp(6), 0, dp(10));
        root.addView(flavor);

        statusView = new TextView(this);
        statusView.setText("Ready");
        statusView.setTextSize(14);
        statusView.setTextColor(Color.rgb(255, 213, 79));
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, 0, 0, dp(10));
        root.addView(statusView);

        // Big round mic button
        Button mic = new Button(this);
        mic.setText("🎤");
        mic.setTextSize(54);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.rgb(27, 122, 67));
        mic.setBackground(circle);
        LinearLayout.LayoutParams micLp = new LinearLayout.LayoutParams(dp(170), dp(170));
        micLp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(mic, micLp);
        mic.setOnClickListener(v -> startSingleCapture());

        TextView micHint = new TextView(this);
        micHint.setText("Mic chap diye kotha bolo");
        micHint.setTextColor(Color.LTGRAY);
        micHint.setGravity(Gravity.CENTER);
        micHint.setPadding(0, dp(6), 0, dp(12));
        root.addView(micHint);

        // Language toggle BN / EN
        langGroup = new RadioGroup(this);
        langGroup.setOrientation(RadioGroup.HORIZONTAL);
        langGroup.setGravity(Gravity.CENTER);
        RadioButton bn = new RadioButton(this);
        bn.setText("বাংলা");
        bn.setTextColor(Color.WHITE);
        bn.setId(View.generateViewId());
        RadioButton en = new RadioButton(this);
        en.setText("English");
        en.setTextColor(Color.WHITE);
        en.setId(View.generateViewId());
        langGroup.addView(bn);
        langGroup.addView(en);
        langGroup.check(bn.getId());
        root.addView(langGroup);

        // Wake mode switch
        wakeSwitch = new Switch(this);
        wakeSwitch.setText("Wake mode — \"KUTU\" bollei sunbo (app khola thakle)");
        wakeSwitch.setTextColor(Color.WHITE);
        wakeSwitch.setTextSize(15);
        wakeSwitch.setPadding(0, dp(10), 0, dp(10));
        root.addView(wakeSwitch);
        wakeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                if (!ensureAudioPermission()) {
                    wakeSwitch.setChecked(false);
                    return;
                }
                state = WAKE_LISTEN;
                setStatus("Wake mode ON — \"KUTU\" bolo…");
                startListening();
            } else {
                state = IDLE;
                stopListening();
                setStatus("Wake mode OFF");
            }
        });

        // Transcript (big, editable)
        transcriptView = new EditText(this);
        transcriptView.setHint("Tumi ja bolba ekhane uthbe…");
        transcriptView.setTextSize(20);
        transcriptView.setTextColor(Color.WHITE);
        transcriptView.setHintTextColor(Color.GRAY);
        transcriptView.setGravity(Gravity.TOP);
        transcriptView.setMinLines(3);
        transcriptView.setBackgroundColor(Color.rgb(22, 40, 30));
        transcriptView.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.setMargins(0, dp(8), 0, dp(10));
        root.addView(transcriptView, tLp);

        // Action buttons
        Button send = new Button(this);
        send.setText("Send to KUTU ➤");
        send.setTextSize(17);
        root.addView(send, fullWidth());
        send.setOnClickListener(v -> sendToKutu());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button copy = new Button(this);
        copy.setText("Copy");
        copy.setOnClickListener(v -> copyTranscript());
        row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button test = new Button(this);
        test.setText("🔊 Test robot voice");
        test.setOnClickListener(v -> speak("আমি কুটু রোবট। বলো বস, কি করতে পারি?"));
        row.addView(test, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(row, fullWidth());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ------------------------------------------------------------- actions

    private void sendToKutu() {
        String text = currentTranscript();
        if (text.isEmpty()) {
            toast("Age kotha bolo ba lekho");
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(send, "Send to KUTU"));
    }

    private void copyTranscript() {
        String text = currentTranscript();
        if (text.isEmpty()) {
            toast("Copy korar moto kichu nai");
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("KUTU Voice", text));
            toast("Copy hoyeche ✅");
        }
    }

    private String currentTranscript() {
        return transcriptView.getText().toString().trim();
    }

    // ------------------------------------------------------------ TTS robot

    private void initTts() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                // Robot flavour: low pitch, near-normal rate.
                tts.setPitch(0.6f);
                tts.setSpeechRate(0.95f);
                ttsReady = true;
            } else {
                ttsReady = false; // toast-only failure handling, never crash
            }
        });
    }

    private void speak(String text) {
        if (tts != null && ttsReady) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kutu-voice");
        } else {
            toast(text);
        }
    }

    // ------------------------------------------------------- speech capture

    private String languageTag() {
        int checked = langGroup.getCheckedRadioButtonId();
        // First radio (index 0) is Bangla, second is English.
        View bn = langGroup.getChildAt(0);
        return (bn != null && checked == bn.getId()) ? "bn-BD" : "en-US";
    }

    private Intent recognizerIntent() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag());
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        return i;
    }

    private void startSingleCapture() {
        if (!ensureAudioPermission()) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("Ei phone e speech recognition nai");
            return;
        }
        state = SINGLE;
        setStatus("Sunsi… bolo 🎙️");
        startListening();
    }

    private synchronized void startListening() {
        try {
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this);
                recognizer.setRecognitionListener(this);
            }
            if (recognizerBusy) return;
            recognizerBusy = true;
            recognizer.startListening(recognizerIntent());
        } catch (Exception e) {
            recognizerBusy = false;
            scheduleRestart(600);
        }
    }

    private void stopListening() {
        try {
            if (recognizer != null) {
                recognizer.stopListening();
                recognizer.cancel();
            }
        } catch (Exception ignored) {
        }
        recognizerBusy = false;
    }

    /** Restart the recognizer once, after a small delay — used by the wake loop. */
    private void scheduleRestart(long delayMs) {
        if (restartPending) return;
        restartPending = true;
        handler.postDelayed(() -> {
            restartPending = false;
            if (state == WAKE_LISTEN || state == WAKE_COMMAND) {
                startListening();
            }
        }, delayMs);
    }

    private boolean hasWakeWord(ArrayList<String> texts) {
        if (texts == null) return false;
        for (String t : texts) {
            if (t == null) continue;
            String low = t.toLowerCase();
            if (low.contains("kutu") || t.contains("কুটু")) return true;
        }
        return false;
    }

    private void onWakeWordHeard() {
        beepAndVibrate();
        speak("বলো বস");
        state = WAKE_COMMAND;
        setStatus("Haa boss, sunsi — command bolo 🎙️");
        stopListening();
        scheduleRestart(350);
    }

    private void onCommandCaptured(String text) {
        transcriptView.setText(text);
        speak("শুনেছি বস");
        if (wakeSwitch.isChecked()) {
            state = WAKE_LISTEN;
            setStatus("Command paisi ✅ Wake mode ON — abar \"KUTU\" bolo…");
            scheduleRestart(700);
        } else {
            state = IDLE;
            setStatus("Command paisi ✅ Ekhon \"Send to KUTU\" chap dao");
        }
    }

    private void beepAndVibrate() {
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 160);
            tg.release();
        } catch (Exception ignored) {
        }
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(130);
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------- RecognitionListener glue

    @Override public void onReadyForSpeech(Bundle params) { }
    @Override public void onBeginningOfSpeech() { }
    @Override public void onRmsChanged(float rmsdB) { }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { recognizerBusy = false; }
    @Override public void onEvent(int eventType, Bundle params) { }

    @Override
    public void onPartialResults(Bundle partialResults) {
        if (state != WAKE_LISTEN) return;
        ArrayList<String> texts = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (hasWakeWord(texts)) onWakeWordHeard();
    }

    @Override
    public void onResults(Bundle results) {
        recognizerBusy = false;
        ArrayList<String> texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        String best = (texts != null && !texts.isEmpty() && texts.get(0) != null) ? texts.get(0) : "";

        switch (state) {
            case WAKE_LISTEN:
                if (hasWakeWord(texts)) {
                    onWakeWordHeard();
                } else {
                    scheduleRestart(250); // keep the wake loop going
                }
                break;
            case WAKE_COMMAND:
            case SINGLE:
                if (!best.isEmpty()) {
                    onCommandCaptured(best);
                } else if (state == WAKE_COMMAND) {
                    state = WAKE_LISTEN; // nothing heard — back to wake listening
                    scheduleRestart(400);
                } else {
                    state = IDLE;
                    setStatus("Kichu sunte pai nai, abar try koro");
                }
                break;
            default:
                break;
        }
    }

    @Override
    public void onError(int error) {
        recognizerBusy = false;
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            state = IDLE;
            if (wakeSwitch.isChecked()) wakeSwitch.setChecked(false);
            toast("Mic permission lage — settings theke dao");
            setStatus("Mic permission nai");
            return;
        }
        if (state == WAKE_COMMAND) {
            // Missed the command — fall back to wake listening if wake mode is on.
            state = wakeSwitch.isChecked() ? WAKE_LISTEN : IDLE;
        }
        if (state == SINGLE) {
            state = IDLE;
            setStatus("Kichu sunte pai nai, abar try koro");
            return;
        }
        if (state == WAKE_LISTEN) {
            scheduleRestart(500); // no-speech / network blips must not kill the loop
        }
    }

    // ------------------------------------------------------------ lifecycle

    private boolean ensureAudioPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
        toast("Mic permission dao, tarpor abar try koro");
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                toast("Mic permission paisi ✅");
            } else {
                toast("Mic chara sunte parbo na");
            }
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try {
            if (recognizer != null) {
                recognizer.destroy();
                recognizer = null;
            }
        } catch (Exception ignored) {
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        super.onDestroy();
    }

    private void setStatus(String s) {
        statusView.setText(s);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
