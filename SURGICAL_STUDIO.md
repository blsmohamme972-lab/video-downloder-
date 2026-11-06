# Surgical Studio AI overlay ZIP (schema v1)

Surgical Studio is built into StreamSave. The original downloader and the downloaded video remain unchanged.

## Workflow

1. Download a YouTube video using StreamSave, then tap Study on the completed download. Alternatively, open Surgical Studio and choose a local video.
2. Have an external multimodal AI review the full video and its narration, then ask it to create an annotation ZIP using the schema below.
3. In the player tap Import ZIP. Select the generated ZIP. The original video plays with drawings, notes and pointer overlays synchronized to timestamps.
4. Pause or seek: annotations follow playback. Hide overlays temporarily with one button.

AI analysis and ZIP generation do not run inside this Android app. The ZIP is imported for the current session only, not yet persisted across app restarts. The native VideoView player may reject some MKV/codec combinations. Tracking must be provided as keyframes by the AI: the app does not independently detect anatomy.

## ZIP format

Root entry: manifest.json (UTF-8). Optional image entries: images/name.png. This is a **data-only** package: scripts, code, and remote references are never executed.

Example manifest.json:

```json
{
  "schemaVersion": 1,
  "title": "Vessel identification example",
  "durationMs": 90000,
  "cues": [
    {"id":"intro","type":"note","startMs":2000,"endMs":8000,
     "x":0.5,"y":0.05,"text":"Visualize the dissection plane.","color":"#64E8BF"},
    {"id":"pointer","type":"arrow","startMs":10000,"endMs":20000,
     "x":0.2,"y":0.2,"x2":0.6,"y2":0.6,"color":"#FFCE79",
     "keyframes":[
       {"timeMs":10000,"x":0.2,"y":0.2,"x2":0.6,"y2":0.6},
       {"timeMs":15000,"x":0.3,"y":0.2,"x2":0.65,"y2":0.55},
       {"timeMs":20000,"x":0.4,"y":0.3,"x2":0.7,"y2":0.5}
     ]},
    {"id":"target","type":"ring","startMs":21000,"endMs":26000,"x":0.7,"y":0.5},
    {"id":"caption","type":"label","startMs":21000,"endMs":26000,
     "x":0.5,"y":0.85,"text":"Confirm anatomy before proceeding"},
    {"id":"diagram","type":"image","startMs":28000,"endMs":34000,
     "asset":"images/anatomy.png","x":0.65,"y":0.08,"x2":0.95,"y2":0.38}
  ]
}
```

Valid types: note, label, arrow, ring, box, image. Cue intervals are [startMs, endMs), in milliseconds from the **beginning of the exact original video**. Coordinates x, y, x2, y2 are normalized fractions in [0,1] of the actual video image; arrow endpoints use first and second coordinate pairs. PNG and box regions use opposite corners. Keyframes must be ordered, within the cue interval, and are linearly interpolated.

Parser limits: max 48 ZIP entries; 32 MiB total inflated bytes; 2 MiB manifest; 4 MiB per PNG; 2500 cues; 300 keyframes per cue. On-screen painting draws a maximum of 30 active cues. Paths are validated against traversal.

## Suggested AI agent instruction

Analyze the complete surgical video and its voiceover. Output only a ZIP with root manifest.json and optional images/*.png using the StreamSave schemaVersion 1. Create precise timestamped notes, labels, rings, arrows, and tracked arrows when justified by visible frames. Use normalized coordinates and ordered keyframes when moving structures require tracking. Do not fabricate anatomy or timing; document uncertainty in captions. Do not export or render another video. The ZIP must be declarative data, not executable code.

## Safety and limits

For education, not clinical decision support. AI may misidentify anatomy, instruments, or technique. Qualified surgical review is required before relying on annotations. Obtain permission and de-identify patient videos before uploading to any AI provider. Download only content you are authorized to use.
