# KUTU Voice

Package: `com.antor.kutuvoice` · version 1.0 (versionCode 1) · minSdk 26 · targetSdk 34 · pure Java, no external libraries, no network, no server, no accessibility service.

## What it is

A Siri-style voice front-end for **KUTU** — the assistant Antor talks to in his
Muse chat. Antor asked for "Siri, but the brain is you". The honest split:

- **This app is the ear and the mouth.** It listens with Android's own speech
  recognition, shows the words on screen, and speaks short confirmations back
  in a robot-flavoured voice (device TextToSpeech at pitch 0.6, rate 0.95).
- **The brain is KUTU in the Muse chat.** The app has no brain of its own and
  no direct API to Muse. Antor's spoken words reach KUTU through Android's
  share sheet: **Send to KUTU** → pick Muse → the text lands in the chat,
  where KUTU actually reads and answers it.

## How the flow works

1. Open **KUTU Voice**. Allow the microphone permission when asked.
2. Either:
   - Tap the big 🎤 button and speak once, **or**
   - Turn on **Wake mode**: while the app stays open it listens in a loop.
     Say **"KUTU"** (or "কুটু") — the phone beeps and vibrates, says "বলো বস",
     and your **next** sentence is captured as the command.
3. The command lands in the big transcript box (fully editable — fix any
   misheard words before sending). KUTU Voice confirms out loud: "শুনেছি বস".
4. Tap **Send to KUTU** and choose Muse in the share sheet. (Or **Copy** and
   paste it anywhere.)
5. KUTU answers in the chat — as text, or as a robot-type voice note.

Language toggle: **বাংলা (bn-BD)** by default, **English (en-US)** optional.
A **🔊 Test robot voice** button speaks a sample line so Antor can hear the
robot voice any time.

## Honest limits (v1)

- **Wake word works only while the app is open.** Always-on listening on a
  locked phone is **NOT** in v1 — Android restricts background microphone use,
  and keeping the mic hot all day would drain the battery.
- Speech recognition is Android's own (usually Google's engine): accuracy for
  Bangla varies, and on many phones it needs an internet connection and the
  Google app present. Errors are normal — that's why the transcript is
  editable before sending.
- Wake-word matching is best-effort: it checks partial and final transcripts
  for "kutu"/"কুটু". Background noise can cause misses or false triggers.
- Sending needs one share-sheet tap. There is deliberately **no** invented
  "direct API to Muse" — the share intent is the real, working route.
- TTS robot voice depends on the phone's TextToSpeech engine; if none is
  available the app falls back to toasts and never crashes.

## Building

Gradle cannot run in the build VM (sandbox loopback restriction — same reason
as the KUTU Phone Agent), so builds use the manual aapt2/d8 pipeline:

```bash
cd ~/workspace/kutu-voice
export KUTU_STORE_PW=... KUTU_KEY_PW=...   # shared KUTU keystore credentials
./build-manual.sh
# → build/outputs/kutu-voice.apk
```

## Signing

Signed with the **shared KUTU keystore** (same one as KUTU Phone Agent:
`~/workspace/phone-agent/hello/kutu-release.keystore`, alias `kutu`). Future
versions of this app must be signed with that same keystore or Android will
refuse to install them as updates.
