# Roadmap: from the native LilyPond engine to a pen-first tablet editor

## Context
**Done:** LilyPond 2.26 runs natively in an APK.
- Verified on the user's Galaxy Tab S7 FE: self-test 8/8, ~0.5 s per small render.
- The APK is 22 MB.
- Repo: github.com/StevenTomer/lilypond-android. CI is written; its first run is in progress.

**Goal:** a tablet editor with two ways to edit:
- **Frescobaldi-style:** tap a note in the score and the editor jumps to its place in the LilyPond source, and the reverse.
- **Denemo-style:** real music editing on the score (enter notes, change pitch and duration, add articulations) without typing.

Handwriting source text and drawing notes by hand are out of scope.

**Hard requirements:**
1. **Several voices on one staff.** Temporary polyphony like `<< { c4 e g e } \\ { c,2 g' } >>` must work in every feature, not just single-line melodies:
   - selection and jump-to-source
   - score editing
   - note entry
   - playback

   Chords (`<c e g>4`) also count.
2. **MIDI output must work.** It must work even when the user's file has no `\midi` block, and it must play on the tablet.
3. **A MIDI keyboard writes LilyPond:** connect the tablet to a digital piano's USB MIDI port (e.g. Yamaha DGX-670) and play, and the notes are written into the source, like Frescobaldi's MIDI input.
4. **MIDI file import:** open a `.mid` file and get LilyPond source.

This plan covers the pieces needed before the editor and the editor phases after them.

## Status (2026-11-22)
- **M0:**
  - CI is green: native build in 47 min uncached, app, emulator tests on API 30 and 35.
  - The update test passed: 2.27.5 built and passed all tests with no source changes.
  - Still open: the app name and package id.
- **M1 (engine):**
  - Done: MIDI for every score, the score map (voices, chords, page boxes exact to
    LilyPond's PDF links), `renderFile` with includes, cancellation.
  - Benchmark on the emulator:
    - startup 0.5 s
    - 1-page chorale 1.0 s
    - 15-page piano piece 11 s (+25% with score map and MIDI)
    - 3-page orchestral score 3 s

    Engraving dominates on long scores, so a warm render server would save little.
    `showLastLength` only gets the 15-page piece down to 4.3 s, because LilyPond still
    interprets all the music.
  - **Change:** the fast edit view renders an *extracted passage* instead. python-ly
    extracts the bars around the edit, converts them to absolute pitches, and adds the
    clef, key and time signature in effect there.
  - Tablet numbers: pending (the harness has a Benchmark tab).
- **M2 (`:scoreview`), first cut done:**
  - Pages are drawn with Android's own `PdfRenderer` instead of MuPDF: hit-testing comes
    from the score map, so MuPDF's extra features aren't needed, and it would add about
    7 MB per ABI. Switching later stays easy, because the view only needs page bitmaps.
  - `ScoreLookup`: tap to object (nearest within 0.6 staff spaces; note heads beat
    articulations), object to source, and cursor to objects. Both directions tell the
    voices of a `\\` passage apart. Unit tests run on the JVM.
  - `ScoreView` (Compose): pages under one another, pinch zoom, sideways pan when zoomed,
    selection drawn in voice colours, and a pen-hover outline.
  - The harness **Score** tab: editable source, auto-render 0.6 s after typing stops
    (a newer edit cancels the running render), tap a note to select its source, move the
    cursor to highlight the note, Play for MIDI, and Go to error.
  - Still to do: lasso and range selection, the hover ghost note (the pitch under the
    pen), tiled rendering for deep zoom (pages are drawn sharp up to 2.5x, then scaled).
- **M3 spike (`:pyspike`):**
  - Chaquopy 17.2 + Python 3.14 + python-ly 0.9.10 run on Android with AGP 9.5.
  - Python starts in 0.9 s; the python-ly import takes 80 ms.
  - rel2abs handles the `\\` case exactly as LilyPond does. Transposition keeps
    formatting and changes the key signature.
  - Cost: about +20 MB per ABI.
- **M5:** the harness has a MIDI tab (device list, notes and chords as LilyPond, delivery
  delay, test chord to the keyboard), waiting for the DGX-670 test.

## Core decision: the LilyPond text is always the source of truth
Every edit becomes a text edit, whether made in the source or on the score. LilyPond re-renders after each one. Denemo keeps its own music model and generates LilyPond from it, which is why it can't edit arbitrary hand-written LilyPond. Here the "music model" is a parse of one music expression (via python-ly). Edits to it are written back as minimal text changes, and everything else in the file stays untouched. Expressions the model can't represent (Scheme, unusual macros) stay text-only: selectable and jump-to-source, but not structurally editable.

## Pieces to build first

### M0 Finish the foundation (small)
- Update drill: cross-compile 2.27.5 for both ABIs and run the self-test (host build already finished OK).
- Get the CI run green: fix whatever the first real run turns up.
- Pick the real app name and package id. `org.lilyengine` is a placeholder, and the id can't change after a public release.

### M1 Engine v2 (`engine/`, `LilyPondEngine.kt`)
- **Cancellation:** kill the running LilyPond process when a newer edit arrives. The editor debounces typing, about 300 ms.
- **Projects:** render a file in place with include paths (`-I`), for multi-file scores. Output goes to a cache dir.
- **MIDI output, always available:** an engine option loads Scheme that wraps the top-level score handler and adds a `\midi { }` output definition to every `\score` that lacks one. LilyPond then writes `.midi` next to the PDF without touching the user's file. Scores that already have `\midi` are left as they are. The `.midi` file comes back in `Result.outputs`.
- **Score map (key enabler, spike first):** a Scheme file passed with `-e`/`--init`-style loading, with no LilyPond patch. During engraving it writes a JSON sidecar:
  - For every note head, rest, articulation, dynamic, slur and bar line: page, bounding box, source file/line/column, grob type, staff position, musical moment, voice/context id.
  - Per system: staff-line positions and bar positions.

  **Voices:** every entry carries its Voice context. For `\\` polyphony that's the implicit voices "1" and "2" inside the `<< >>`, plus the source range of the voice's `{ }` block. So two notes at the same moment on the same staff are told apart, and an edit goes into the right `{ }`. Chord notes share one chord entry, and each note head keeps its own pitch and location.

  Point-and-click PDF links give only boxes plus locations. The score map adds what score editing needs: which pitch a tap on an empty staff position means, and where to insert.
- **Latency measurement on the tablet** with realistic scores (a chorale, a 20-page piano piece, an orchestral page). Split process startup (Guile boot) from engraving time.
  - If startup dominates: a warm render server, i.e. one long-lived LilyPond process running a Scheme loop that calls `ly:parse-file` per job. LilyPond already resets state between files when given several.
  - If engraving dominates: a "fast edit view" that renders only the region being edited (`showFirstLength`/`showLastLength`, or the current system) while the full score renders behind it.

### M2 Score view component
- Reuse SzabadPDF's MuPDF viewer (`com.artifex.mupdf:fitz`, AGPL like this project): multi-page, zoom, continuous scroll, stylus hover tooltips, pressure-aware input. Extract the needed parts of `~/dev/szabadpdf/core` into a module here.
- Add an overlay layer driven by the score map:
  - selection highlights
  - tap and lasso hit-testing
  - a hover "ghost note" showing the pitch under the pen, for later note entry
- MuPDF also exposes the PDF's `textedit://file:line:col` links, a fallback when there's no score map.

### M3 Python runtime + python-ly
- Embed CPython via Chaquopy (MIT; verify the current Python version and minSdk 28 support). The fallback is CPython's official Android build (PEP 738). Expect about +15 MB.
- A Kotlin bridge (`:lilytools` module) around python-ly:
  - tokens for highlighting
  - document parse with positions
  - the music tree for one expression, with relative↔absolute pitch
  - transpose and rhythm operations
  - convert-ly (version updates), musicxml2ly and abc2ly (import)
- This is what turns "change this note's pitch" into a correct text edit, including in `\relative` mode, where changing one note shifts the octave of the notes after it.
- **LilyPond's own Python scripts ship in the runtime:** `midi2ly`, `convert-ly`, `musicxml2ly`, `abc2ly`. Their modules are already in `share/lilypond/<ver>/python`. The packager currently drops `bin/`, so `AndroidPackager.prepare_package` in `native/android_overlay.py` must keep the scripts. They run on the embedded CPython.
- **The music tree must represent simultaneous music:** `<< { … } \\ { … } >>` as parallel voices, each its own sequence, plus chords. Each voice gets its own `\relative` reference pitch. In `<< { c4 e g e } \\ { c,2 g' } >>` the second voice's `c,` is relative to the pitch before the `<<`, not to the first voice's last note; python-ly's relative handling must get this right, and gets unit tests for it.

### M4 Source editor component
- Sora Editor (Rosemoe; verify license, LGPL-2.1 expected) for large-file performance, highlighting from python-ly tokens, and error markers from `Diagnostic`.
- Two-way sync: cursor ↔ score selection.

### M5 MIDI devices (`:midi` module, `android.media.midi`)
- **Connection:** USB MIDI class devices, which the DGX-670's USB TO HOST port is, through a USB-C OTG cable (USB-C to USB-B). Also Bluetooth MIDI.
  - `MidiManager` device discovery, plus hot-plug callbacks.
  - A "MIDI device" picker that remembers the last device.
  - The Tab S7 FE has one USB-C port, so the tablet can't charge while connected unless through a powered hub. Mention this in the UI help.
- **Input parsing:** note on/off with velocity. Keys held together form a chord (a ~60 ms grouping window). Sustain pedal (CC64) and channel handling.
- **Pitch spelling:** MIDI gives only key numbers, so 61 must become `cis` or `des`. Spell from the key signature in effect at the cursor (from python-ly's parse of `\key`), with a manual "respell" (enharmonic flip) on the selection.
- **Output:** send MIDI to the keyboard too, so playback can sound through the DGX-670's own voices, and so can a tapped note.
- **Testing without the keyboard:** a virtual MIDI device (`MidiDeviceService` in the harness) that instrumented tests drive with scripted notes. The emulator has no USB MIDI.

### M6 Playback
- A small in-app MIDI sequencer: parse LilyPond's `.midi`, schedule events. It drives three things:
  - Android's built-in synth (Sonivox via MediaPlayer for whole-file play, or `android.media.midi`'s internal synth where available)
  - an external keyboard from M5
  - later, FluidSynth + a SoundFont
- The sequencer also gives the playback cursor its timing.
- First cut: play LilyPond's MIDI with Android's built-in MIDI player (MediaPlayer). It's General MIDI, so it sounds basic, but it needs no extra libraries.
- Later: FluidSynth + a SoundFont for quality and per-staff instruments.
- A playback cursor driven by score-map moments, highlighting every voice's note at the current moment.
- Tapping a note can play just that note.

## Editor phases (new `:editor` app module; the harness stays as the test bed)

**E1 Frescobaldi-style editor**
- Projects and files through Android's file picker.
- Source editor + live preview (debounced re-render), side by side in landscape, stacked in portrait.
- Point-and-click both ways; error markers.
- A quick-insert palette (articulations, dynamics, slurs, ties) applied to the source selection.
- Transpose; export/share PDF and MIDI.
- **Import:**
  - MIDI file → LilyPond via `midi2ly` (the tool Frescobaldi uses). Options in an import dialog: quantization grid for durations and start times, key signature, and whether to split voices or tracks into staves.
  - Then convert-ly to the current syntax, and open in the editor.
  - midi2ly's output is basic: one voice per track, and it has trouble with expressive timing. A later improvement is a clean-up pass with python-ly that merges ties, applies the key-signature spelling from M5 and detects time-signature bars.
  - MusicXML and ABC import go through `musicxml2ly` and `abc2ly` the same way.

**E2 Editing on the score**
- Select notes with the pen (tap, lasso, range).
- The palette applies to the score selection.
- Drag up/down to change pitch; duration buttons; delete.
- Each action is a python-ly text edit. An optimistic overlay shows the change immediately while LilyPond re-renders.

**E3 Denemo-style note entry**
- An insertion cursor on the score.
- Input:
  - pen tap on a staff position, with the hover ghost showing the pitch
  - a duration palette
  - an on-screen piano
  - a USB/Bluetooth MIDI keyboard (M5), as described in the next bullet
- Edits go into the active music expression, written back as LilyPond text.
- **Voices:**
  - The cursor belongs to one voice. Choose it by tapping one of its notes, or with a voice switcher (1/2/3/4, colour-coded with stem direction).
  - "Add a second voice here" wraps the current measure range in `<< { existing } \\ { new } >>`. "Merge voices" undoes it.
  - Each voice shows when its measure is short or long relative to the others, with a one-tap fix: fill with rests, or spacer rests `s`.
  - Chord entry adds a pitch to the note under the cursor (`c4` → `<c e>4`).
- **MIDI keyboard entry, Frescobaldi parity first:**
  - Step-time: each key or chord played inserts a note or chord at the cursor, in the cursor's voice, with the current duration. Choose the duration from the palette, or from a foot pedal or keyboard key mapped to "next duration".
  - The cursor advances; a rest key or button inserts rests.
  - Works in the source editor too: notes are inserted as text at the text cursor, in `\relative` or absolute mode to match the surrounding music. This part can ship in E1.
- **Real-time recording, later and harder:**
  - Play along to a metronome click, sent to the keyboard or tablet speaker.
  - Capture the timing, then quantize (grid + tuplets) and split into voices by register. The result is written as LilyPond.
  - Preview the quantized result on the score before accepting it.
  - Same pipeline as MIDI file import: record into an in-memory MIDI sequence and run the import path on it.
- Feedback uses the M1 fast edit view, or a lightweight one-voice entry staff drawn in-app with SMuFL glyphs if LilyPond can't keep up. The M1 measurements decide which.

**Later:** score wizard and templates, an ink mark-up layer (SzabadPDF pen), Play Store / F-Droid release.

## Risks: spike these first, in this order
1. **Score map via Scheme:** can a loaded Scheme file collect grob extents, staff positions, moments, origins and Voice contexts for all grobs (including `\\` polyphony and chords) and write JSON without patching LilyPond? The same spike covers the automatic `\midi` injection.
2. **Render latency of real scores on the tablet,** and whether a warm render server works.
3. **python-ly round-trip fidelity:** edits that keep the user's formatting, and correct `\relative` handling.
4. **Chaquopy:** works on minSdk 28, size and startup acceptable. It must also run LilyPond's `midi2ly` and `convert-ly` unmodified.
5. **USB MIDI with the DGX-670 on the Tab S7 FE:** the device enumerates through `MidiManager`, note latency is low enough to feel immediate, and output reaches the keyboard. Test early with the real keyboard; it's a quick spike.

## Verification
- **Shared test corpus**, used by every milestone: single voice; `<< { c4 e g e } \\ { c,2 g' } >>` (in `\relative` and absolute mode); three voices; chords; a piano score with two staves and polyphony in each; a file with no `\midi` block.
- **M0/M1 MIDI:**
  - Instrumented tests assert that a `.midi` file is produced for the no-`\midi` file, starts with `MThd`, and has a track per voice.
  - The self-test adds a "MIDI" check that writes and plays the file through MediaPlayer (prepare succeeds, duration > 0).
  - Then a listening check on the tablet.
- **M1:** instrumented tests render the corpus. Assert that cancellation kills the process and that the score map has an entry for every note head, with the right voice, staff position and source location. For the two-voice example: four entries in voice 1 and two in voice 2, each pointing into its own `{ }`.
- **M1 latency:** a benchmark screen in the harness reports startup vs engraving times on the tablet.
- **M2/M4:** on the tablet, tapping a note in a multi-page score jumps to the right source position, and moving the cursor highlights the right note.
- **M3:** unit tests for python-ly edits compare the text before and after:
  - a pitch change in `\relative`
  - transpose
  - adding an articulation
  - an edit inside voice 2 of a `\\` construct that leaves voice 1 untouched
  - adding a note to a chord
  - "add second voice" and "merge voices"
- **M5:** instrumented tests drive the virtual MIDI device and assert:
  - a chord groups correctly
  - 61 spells as `cis` in G major and `des` in F major
  - step entry inserts the expected text in `\relative` and absolute mode, and into voice 2 of a `\\` construct

  Then a hands-on check with the DGX-670: notes in, playback out through the keyboard.
- **MIDI import:** import a set of MIDI files (one exported by LilyPond from the corpus, a piano piece, and a real-time recording) and check that each gives LilyPond that renders. For the LilyPond-exported file, compare pitches and durations with the original source.
- **E-phases:** each ships as a test build on tomerhome.org/utils/android, with self-test checks extended for the new engine features.
