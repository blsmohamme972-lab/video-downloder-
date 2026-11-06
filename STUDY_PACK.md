# Study packs — drawings, questions and captions for a video

The **Study player** in StreamSave plays a downloaded (or any local) video and, from a *study pack*:

- draws animated arrows, circles, boxes, spotlights, labels and images on the video at the right moments;
- shows captions;
- **pauses after each part of the video and asks a multiple-choice question about the part just watched**, then shows whether the answer is right and why.

You can also add your own notes at any moment. Notes, answers and the pack are saved per video.

The app never changes the video and never runs anything from a pack: a pack is only data.

## How it works: send a Drive link, then press Update

1. Put the video in **Google Drive** and send its link to your AI assistant, for example Claude in this repository's session.
2. The assistant watches and listens to the video and publishes a study pack to this repository's [`study-packs/`](study-packs) folder (see [Publishing a pack](#publishing-a-pack-for-ai-assistants)).
3. In StreamSave, open the video in the **Study player**:
   - for a download, tap **Study** in the Library;
   - for a video from Drive or elsewhere, use **Study player** on the main screen, or share the video to StreamSave.

   The player finds the video's pack in the study library by itself. If it was published moments ago, tap **Update pack**. You never download or pick a file.
4. Press **Play**. The video pauses for each question.

New versions of a pack install themselves the next time the video is opened, and saved answers are kept. **More → Retake all questions** resets them.

Packs are matched to the video by its YouTube id (downloads, including StreamSave's saved files opened from the gallery or Files) or by its file name (other videos). If no pack matches, **Update pack** lists all published packs so you can choose one with one tap. **More → Add a study pack file…** still accepts a pack file for offline use.

Packs in the study library are public, like the rest of this repository: publish packs only for videos you may share, and never include patient-identifying details.

## File format (schemaVersion 2)

A pack is a UTF-8 JSON file. It can also be a ZIP containing `manifest.json` plus optional `images/<name>.png`. All times are milliseconds from the start of the **exact same video**. Positions are fractions (0 to 1) of the video picture: `x` runs left to right and `y` top to bottom.

```json
{
  "schemaVersion": 2,
  "title": "Type A aortic dissection repair",
  "language": "en",
  "durationMs": 4578000,
  "overlays": [
    {"type": "spotlight", "startMs": 61000, "endMs": 66000, "x": 0.35, "y": 0.3, "x2": 0.65, "y2": 0.7,
     "text": "Dissected ascending aorta"},
    {"type": "arrow", "startMs": 70000, "endMs": 78000, "x": 0.15, "y": 0.2, "x2": 0.45, "y2": 0.48,
     "text": "Electrocautery", "color": "#FFCE79",
     "keyframes": [{"timeMs": 70000, "x": 0.15, "y": 0.2, "x2": 0.45, "y2": 0.48},
                   {"timeMs": 78000, "x": 0.2, "y": 0.25, "x2": 0.5, "y2": 0.52}]},
    {"type": "ring", "startMs": 80000, "endMs": 85000, "x": 0.6, "y": 0.55, "x2": 0.68, "y2": 0.55},
    {"type": "label", "startMs": 90000, "endMs": 96000, "x": 0.5, "y": 0.88, "text": "Teflon felt strip"}
  ],
  "questions": [
    {"id": "m01", "startMs": 0, "endMs": 60000,
     "question": "What is the surgeon separating in the first minute?",
     "options": ["The aorta from the pulmonary artery", "The vena cava from the atrium",
                 "The pericardium from the sternum", "The coronary artery from the ventricle"],
     "answer": 0,
     "explanation": "The aorta is freed from the pulmonary artery so it can be clamped and replaced."}
  ],
  "captions": [
    {"startMs": 0, "endMs": 4200, "text": "We start by freeing the ascending aorta."}
  ]
}
```

### Drawings (`overlays`)

| type | uses | draws |
| --- | --- | --- |
| `arrow` | `x,y` → `x2,y2` | an arrow from the first point to the second, with optional `text` at its tail |
| `ring` | centre `x,y`; radius = distance to `x2,y2` | a circle around a structure |
| `box` | corners `x,y` and `x2,y2` | a rounded rectangle |
| `spotlight` | corners `x,y` and `x2,y2` | dims everything outside an oval |
| `label` | centre `x,y`, `text` (required) | a text bubble |
| `note` | centre `x,y`, `text` (required) | a highlighted note bubble |
| `image` | corners `x,y` and `x2,y2`, `asset` | a PNG from `images/` (ZIP packs only) |

**Optional fields:**
- `color`: for example `#64E8BF`.
- `text`: up to 600 characters.
- `id`
- `animation`: `none`, `fade`, `pop`, `draw` (the arrow or box draws itself) or `pulse` (the shape breathes). The defaults are `draw` for arrows, `pulse` for rings, `pop` for labels and notes, and `fade` for everything else.
- `keyframes`: up to 300 `{timeMs, x, y, x2, y2}` entries, in order and inside the drawing's time range. They move the drawing smoothly to track a structure.

### Questions (`questions`)

| field | meaning |
| --- | --- |
| `startMs`, `endMs` | the part of the video the question is about, usually one minute |
| `askAtMs` | when to pause and ask; defaults to `endMs` |
| `question` | up to 500 characters |
| `options` | 2–6 answers, each up to 200 characters |
| `answer` | **0-based** index of the correct option |
| `explanation` | optional, up to 1000 characters |
| `id` | optional unique id; keeps saved answers matched if the pack is replaced |

A question is asked when **playback** passes `askAtMs`; jumping past it with the seek bar does not ask it. A question that has been answered is not asked again until **Retake all questions**.

### Captions (`captions`)

Each caption is `{startMs, endMs, text}`, with `text` up to 300 characters. Overlapping captions are shown together.

### Voice-over (`narration`)

Spoken commentary that the phone creates with Supertone's Supertonic 3 voice. The first time a pack with narration is opened, the app downloads the voice model once (383 MB) from Supertone, in the background; an interrupted download continues where it stopped. It works offline after that.

```json
"narration": [
  {"id": "n01", "startMs": 61000, "text": "Here the surgeon frees the ascending aorta from the pulmonary artery."},
  {"id": "n02", "startMs": 75000, "endMs": 84000, "text": "Notice the electrocautery at the tip of the forceps.", "voice": "M1", "speed": 1.2}
]
```

| field | meaning |
| --- | --- |
| `startMs` | when the line starts |
| `text` | what is said, up to 2000 characters, in the pack's `language` (English: `"language": "en"`) |
| `endMs` | optional: the time by which the line should be finished. If the line is still speaking then, the video waits for it. Without `endMs`, the next line's start is used. |
| `voice` | optional: `F1`–`F5` or `M1`–`M5`, overriding the user's setting (default `F1`) |
| `speed` | optional: `0.7`–`2.0`, overriding the user's setting (default `1.30`) |
| `id` | optional, unique |

**Playback rules:**
- Lines start only when playback passes `startMs`, like questions.
- While the voice-over is on, the video's own sound plays at 15%, and lower still while a line is spoken. The level can be changed in **More → Voice-over settings**.
- A seek, a question or pausing stops the line.

**Writing good lines:**
- Write the text as it should be heard. Spell out numbers and abbreviations where reading matters, for example "4-0 Prolene".
- Keep each line within the time its part of the video lasts. At 1.30× speed, about 3 words per second fit.

### Limits

- The pack can have up to 3000 drawings, 1000 questions and 20000 captions.
- JSON packs can be up to 4 MB.
- ZIP packs can be up to 32 MB unpacked, with 4 MB per image.
- Version 1 annotation ZIPs from the earlier Surgical Studio still open.

## Publishing a pack (for AI assistants)

1. Make the pack as described below, using the prompt in [Prompt for the AI](#prompt-for-the-ai).
2. Save it as `study-packs/<id>.json`, where `<id>` uses lowercase letters, digits and dashes, for example `aortic-dissection-repair`.
3. Add or update its entry in `study-packs/index.json`:

```json
{"schemaVersion": 1, "packs": [
  {"id": "aortic-dissection-repair", "title": "Type A aortic dissection repair",
   "youtubeId": "Ft2bn8LcDDY",
   "fileNames": ["Tip 1 Aort Diseksiyonu – Acil Kalp Ameliyatı.mp4"],
   "version": 1}
]}
```

   - `youtubeId`: set it when the video is on YouTube.
   - `fileNames`: the video's file name(s), for example the Google Drive name. Case, accents and the extension are ignored.
   - `version`: raise it every time the pack changes, so phones replace their copy. Keep question `id`s stable so saved answers stay matched.
4. For `narration`, use only characters the voice supports. `TtsText.unsupported(...)` lists any it cannot say; plain English text with ordinary punctuation is fine. Then run `./gradlew testDebugUnitTest`. `StudyLibraryTest` opens every listed pack with the app's own parser and fails on any problem.
5. Push to `main`. Phones pick it up automatically; no app release is needed.

## Prompt for the AI

> Read the video `<file name>` from my Google Drive. Watch the picture and listen to the narration from start to end. Make a StreamSave study pack (schemaVersion 2) exactly as described in STUDY_PACK.md of github.com/blsmohamme972-lab/video-downloder-, and save it to the same Drive folder as `<file name>.study.json`.
>
> - **Questions:** for every minute of the video (0:00–1:00, 1:00–2:00, …) write one multiple-choice question that tests what was shown or said in that minute. Use 4 options and one correct answer, set `answer` to its 0-based index, and add a short explanation.
> - **Drawings:** add arrows, rings, spotlights or labels only where the picture clearly shows the structure or instrument. Use normalized coordinates of the actual video frame, and keyframes when it moves.
> - **Captions:** add captions from the narration.
> - **Voice-over:** add `narration` lines in clear English that explain what is happening, timed to the moments they describe, short enough to fit before the next line.
> - **Accuracy:** do not invent anatomy, steps or timings. If unsure, leave the drawing out, or say so in the explanation.

## Safety

This is for learning. AI-generated drawings, captions and questions can be wrong; check important content with a qualified teacher. Only upload videos you are allowed to use, and remove patient-identifying information before sharing a video with any AI service.
