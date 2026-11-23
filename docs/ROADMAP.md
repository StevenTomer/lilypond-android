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
  - The app name and package id: AndroLily, `org.androlily` (0.20.0).
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
  - Still to do: tiled rendering for deep zoom (pages are drawn sharp up to 2.5x, then scaled).
- **M1 fast edit view, done (differently than planned):** `Options.window` engraves only a
  stretch of the music. The settings file `window.ly` wraps each score the way LilyPond's
  own `showFirstLength` does: the music is cut off at the window's end, and typesetting is
  off before its start. No python-ly extraction was needed, and the score map keeps
  working in the preview. `ScoreLookup.windowAround` picks the bar before the edit and
  two after, aligned to printed bar lines.
  - Timings: on the host, the 400-bar piece takes 5.1 s in full and 1.3 s windowed. On the
    emulator, 13.7 s in full and 2.1 s windowed.
  - The Score tab: after an edit to a score whose last full render took over 1.5 s, the
    preview shows first, then the whole score renders and scrolls back to the edited note.
- **MIDI import, done (first cut):** LilyPond's own midi2ly, convert-ly and musicxml2ly
  come from the source release matching the engine version. A Gradle task fills in the
  build placeholders (no native rebuild needed), and they run in-process from
  `lilyimport.py`: the module is reloaded for each run, and the console streams are
  in-memory. `LilyTools.importMidi` runs midi2ly, then convert-ly. The Score tab has
  Import MIDI with quantization and triplet options.
  - The round-trip test checks that every pitch and start time comes back.
  - Known: voices sharing a staff come back as chords, because LilyPond writes one MIDI
    track per staff. A clean-up pass that splits them into voices is future work.
- **E2 start:** key-aware step up/down (`lilyedit.step`, all church modes), and dragging
  a selected note head with the pen shows a ghost note at the target position.
- **M3 (`:lilytools`), first cut done:**
  - Chaquopy runs in an Android library module; python-ly 0.9.10 is pinned.
  - `lilyedit.py` has set pitch, set duration, delete, transpose and rel2abs. python-ly's
    rel2abs is the reference for absolute pitches. An edit writes the change, then
    corrects octave marks note by note until every other note sounds as before. So
    `\\relative`, chords and `\\\\` voices need no special code. A new duration
    writes out the next note's old duration when that note had none of its own.
  - The Kotlin `LilyTools` returns the smallest single `TextEdit`. Tests: 14 host Python
    tests (CI), Kotlin unit tests, 3 instrumented tests that compare LilyPond's engraving
    before and after, and a self-test check.
  - The Score tab has an edit bar: step up/down, sharp/flat/natural, durations, dot,
    delete, undo.
  - Cost: the arm64 APK went from 22.5 to 35.5 MB.
  - Still to do: midi2ly / convert-ly / musicxml2ly from LilyPond's own scripts, and
    key-aware spelling for step up/down (it currently writes naturals).
- **M3 spike (`:pyspike`, superseded by `:lilytools`):**
  - Chaquopy 17.2 + Python 3.14 + python-ly 0.9.10 run on Android with AGP 9.5.
  - Python starts in 0.9 s; the python-ly import takes 80 ms.
  - rel2abs handles the `\\` case exactly as LilyPond does. Transposition keeps
    formatting and changes the key signature.
  - Cost: about +20 MB per ABI.
- **M5:**
  - The `:midi` module has `MidiKeyboard` (hot-plug, connects to the first device,
    events on the main thread, output to the keyboard), a running-status `MidiParser`
    and `ChordCollector` (keys held together form one chord, closed when all are up).
  - Step entry (0.8.0): the Score tab's Enter notes mode writes notes and chords from
    the keyboard, or from an on-screen piano, after the selected note.
    `lilyedit.insert` spells them for the key at that point (`spell`: twelve fifths
    around the key signature, shifted for minor) and sets octave marks so they sound
    as played in `\relative` and absolute music, and in either voice of `\\`.
  - Still to do: the hands-on test with the DGX-670.
- **M6 (0.9.0):**
  - `MidiFile` reads LilyPond's MIDI (tracks merged, tempo map), and `MidiPlayer` plays
    it to the keyboard on a timing thread, releasing sounding notes when stopped.
  - The Score tab plays through the keyboard when one is connected ("Sound: keyboard" /
    "Sound: tablet"), from the selected note if there is one.
  - A playback cursor highlights the notes and rests sounding in every voice
    (`ScoreLookup.soundingAt`) and keeps them in view.
  - Limits: MIDI ticks are mapped to moments directly, so repeats unfolded only in MIDI
    and grace notes put the cursor off. FluidSynth is still to come.
- **0.9.1, fixes from the first tablet tests:**
  - Big MIDI imports: midi2ly turns a 13-track song into ~120 pages, which took longer
    than the old 300 s limit on the tablet. The first 4 whole notes are now engraved
    first. The whole score then renders with a 30-minute limit, and the status shows
    LilyPond's phase and bar (`RenderProgress`, fed by `Options.onOutput`).
  - Fast-preview windows crashed LilyPond (signal 11) when the window ended at a bar
    line that a note split by `Completion_heads_engraver` crosses, as midi2ly's output
    uses it. LilyPond's own `showFirstLength` crashes the same way. `window.ly` now
    stops the music 1/1024 before the window's end, and no longer writes MIDI when the
    score has its own `\midi` block (it tested a property that 2.26 doesn't set).
  - A finger long press grabs the nearest note within 24 dp and selects it if it
    wasn't selected. The drag is summed from each move, so the selection info growing
    doesn't make the note jump.

- **0.10.0, LilyPond as a MIDI device:**
  - The app is a MIDI device named "LilyPond" that other MIDI apps on the tablet see
    (`SharedMidiDevice`, a `MidiDeviceService` in `:midi`). Notes played into its input
    reach `MidiKeyboard`'s listeners like a USB keyboard's, so note entry and the MIDI
    tab take them. Its output carries what LilyPond plays: "Sound: MIDI apps" sends
    score playback there when an app listens, and the on-screen piano always does.
  - The on-screen piano now goes through `MidiKeyboard.tap`, the same path as a
    keyboard. `MidiKeyboard` never offers LilyPond's own device as a keyboard.
  - Tests: `SharedMidiDeviceTest` opens the device as another app would.
    `UsbKeyboardTest` uses `TestKeyboardService`, a stand-in USB keyboard in debug
    builds only, enabled and disabled to plug and unplug it: notes, running status,
    chords, step entry into voice 2, sending, playback, and replugging.
  - Fixed: `MidiKeyboard.start` looked for devices before watching for new ones, so a
    keyboard plugged in between was missed; and a device reported twice was opened
    twice.

- **0.11.0, marks on the score (E1 palette, E2 on the score):**
  - `lilyedit.toggle` adds or removes one mark on the note, chord or rest at a
    position. The marks are articulations and ornaments, dynamics (one per note, so a
    new one replaces the old), slur start and end, hairpin start (`\<` / `\>`, which
    replace each other) and end (`\!`), and ties. `lilyedit.marks` lists those already
    there, in any direction (`-.`, `^.`, `_.`).
  - It edits only the text after the item's duration. A note inside a chord means the
    chord. In voice 2 of `\\`, only voice 2 changes.
  - Fixed on the way: `_item_end` took the `>` of an accent (`->`) or a decrescendo
    (`\>`) for the end of a chord, so `delete` left them behind.
  - The Score tab has a marks row under the edit bar. The marks on the selected note
    are filled, and tapping one takes it off.
  - Tests: 10 Python tests, plus an instrumented test that renders the result and
    checks, from the score map, that each Script, DynamicText and Slur is in the
    right voice.

- **0.12.0, range selection (E2):**
  - The pen draws a box (from anywhere but a selected note head), and the note heads
    and rests it touches are selected, on one page. A finger still scrolls.
    `ScoreView.onSelectRange`.
  - `lilyedit.multi` applies one edit to every item of a range, last first so the
    earlier positions stay valid: step, accidental, duration, dot, delete, or a mark.
    Marks are added to all, or taken off if all have them. Chord notes count once,
    rests are skipped by pitch edits, and repeated durations are dropped
    (`c8 e8 g8` becomes `c8 e g`).
  - `lilyedit.span` draws a slur or hairpin from the first to the last item of each
    voice of the range.
  - Both return where the selected items are afterwards: notes are found again by
    note index, other items by item index. So the range stays selected across edits
    and re-renders.
  - The Score tab: the edit bar and marks row act on the whole range, and with a
    range the Lines group becomes "slur ( )", "cresc <" and "dim >" over it. The info
    line counts the notes and rests and the voices.
  - Tests: 10 Python tests, plus an instrumented test across both voices of `\\`:
    step, staccato, a slur per voice, and delete, checked against LilyPond's
    engraving.

- **0.13.0, adding and removing voices (E3):**
  - `lilyedit.add_voice` turns one stretch of one voice (the selected note or range)
    into `<< { …the notes… } \\\\ { rests } >>`. The rests are exactly as long as the
    stretch (duration arithmetic with fractions; `_durations_for` writes 7/8 as
    `2. 8`).
  - Durations that the `<< >>` would change are written out: the first note inside,
    and the item after the `>>`, since LilyPond carries on with the rests' last
    duration. Octaves are corrected so everything sounds as before.
  - `lilyedit.remove_voice` removes the voice the selection is in. With one voice
    left, its music replaces the `<< >>`, with durations and octaves kept.
  - Note entry fills rests (`lilyedit.fill`, the "Fill rests" switch, on by default):
    a note played on a selected rest, or on the rest right after the selected note in
    the same voice, takes its place, and the remainder stays as rests. A note at the
    start of a voice always gets its duration written.
  - The Score tab: "Add voice" selects the new voice's first rest and turns on note
    entry. "Remove voice" works on a note in a `\\\\` passage.
  - Tests: 9 Python tests, plus an instrumented test: add a voice, fill it from the
    keyboard path, check both voices in LilyPond's engraving, then remove each voice.

- **0.14.0, note entry on the staff (E3):**
  - `ScoreLookup.pitchAt` turns a point into a staff line or space. The staff comes
    from the StaffSymbol boxes, and ledger lines are reached up to 4 staff spaces
    beyond the staff. The note comes from the clef in effect, read off the nearest
    note head on that staff in the same system.
  - A note head belongs to the staff that puts its staff position where it is
    drawn, not simply the nearest staff: a treble note on ledger lines between the
    staves is nearer the bass staff.
  - In Enter notes mode, `ScoreView.onStaffTap` lets a tap on a line or space enter
    that note. The key signature gives its accidental (`lilyedit.keys_for_steps`, and
    `LilyTools.insertStep`), and it sounds on a connected keyboard. The pen's hover
    shows a ghost note there, with ledger lines.
  - Tests: a unit test (every note head's centre gives its own pitch, and one step
    up the next note), an instrumented test on the two-staff piano sample, and 2
    Python tests.

- **0.15.0, voice lengths (E3):**
  - `lilyedit.voice_lengths` finds every `<< … \\\\ … >>` (nested ones too) whose
    voices aren't equally long, adding up each voice's durations. Passages with
    tuplets, grace notes, repeats, nested `<<` or variables are skipped.
  - LilyPond doesn't warn about this. A `<< >>` lasts as long as its longest voice,
    so the music after it stays in time, but the shorter voice ends early and its
    later notes no longer line up with the bars.
  - `lilyedit.pad_voices` puts rests at the end of the shorter voices, and writes
    out the duration of the item after the `>>` if it relied on the last one in the
    passage.
  - The Score tab checks 0.7 s after each change, and shows "⚠ Line N: voices of
    unequal length" with "Go there" and "Fill with rests".
  - Tests: 6 Python tests, plus an instrumented test that checks LilyPond's
    engraving: the music after starts when the longest voice ends, and padding adds
    the rest in voice 2.

- **0.16.0, MusicXML import:**
  - `lilyimport.musicxml_to_ly` runs LilyPond's own musicxml2ly in-process, then
    convert-ly. It takes plain `.musicxml` / `.xml` and compressed `.mxl` (detected by
    the zip's first bytes).
  - By default the file's page size, margins and breaks are dropped, since they were
    made for another page; the import dialog can keep them, and offers absolute
    pitches. Voices stay voices (named Voice contexts), and parts become staves.
  - The Score tab's Import tells MIDI from MusicXML by the file's first bytes, not
    its name or type.
  - Marks written after a space (`b4 -.`, as musicxml2ly writes them) now belong to
    the note: `_item_end` reaches them, so the marks row sees them and delete
    removes them.
  - Tests: an instrumented test imports the sample, plain and as a `.mxl`, and checks
    from LilyPond's engraving: both voices on the upper staff, the lower part on its
    own staff, and the staccato. A 14th self-test check uses the same sample
    (`assets/samples/two-voices.musicxml`). There is also 1 Python test.

- **0.17.0, fixes from the second tablet test:**
  - Dragging a note: you couldn't see where it would land, since a finger or the pen
    hides the note and one step was a few pixels.
    - A large pitch label (e.g. "fis''") now floats 70 dp above the pen or finger,
      spelled for the key signature LilyPond printed. The score map now records each
      KeySignature's alterations (`keyAlterations`), read by `ScoreLookup.keyAt` and
      `nameAfterSteps`.
    - The ghost note gets ledger lines, and each step gives a light tick.
    - A step needs at least 12 dp of pen movement or 18 dp of finger movement,
      whatever the zoom.
  - Entering notes on the staff between two notes was hard or impossible. In Enter
    notes, a tap (or hover) picks a note only when it is right on a note head or
    rest; anywhere else on the staff is a place for a new note, slurs and hairpins
    included.
  - The selection info line is now always one line, so selecting no longer moves the
    score under the pen.
  - Play after a MIDI import plays the MIDI file itself until the whole score has
    been engraved, which takes minutes for a big file. After a MusicXML import, the
    previous score's MIDI is dropped.

- **0.18.0, more fixes from the second tablet test:**
  - Rotating the tablet lost all work: the activity was recreated. It now handles
    configuration changes itself (`android:configChanges`), so the score, undo, a
    render in progress, playback and the MIDI keyboard all survive.
  - The source is autosaved (`files/score-draft.ly`) a second after each change and
    reopened at start, so switching tabs or Android closing the app in the background
    doesn't lose it either.
  - Play was silent after a big MIDI import. Play kept the last render's MIDI file,
    which the engine deletes when the next render starts, and the error message was
    overwritten by the progress line. The Score tab now copies the MIDI it plays.
  - MIDI import can leave out tracks (`lilyimport.midi_tracks` and `_keep_tracks`,
    which write a MIDI file with only the chosen tracks, plus the ones without notes,
    for midi2ly). The import dialog lists each track's name, General MIDI instrument
    family and number of notes; drum tracks start unticked, since midi2ly writes drums
    as pitched notes. The second test file (13 tracks, 96 pages) took 691 s on the
    tablet, so fewer staves are the biggest saving.

- **0.19.0, chords, respelling, files:**
  - `lilyedit.add_to_chord` adds notes, spelled for the key, to the note or chord at
    a position (`c4` becomes `<c e>4`), with octaves kept in `\relative` and in
    either voice. Enter notes has a Chord switch: notes from the keyboard, the piano
    or a staff tap join the selected chord.
  - `lilyedit.respell` writes a note's other spelling at the same pitch (`cis` and
    `des`, `e` to `fes`, `c` to `bis`), also over a range, where notes without a
    simple one are left alone. It is the edit bar's ♯⇄♭ button.
  - Files: a "File ▾" menu in the Score tab with New, Open…, Save, Save as… (Android's
    document picker, with access kept across restarts), Share PDF, Share MIDI and
    Share LilyPond source (through a FileProvider), and the samples.
    - The menu shows the file's name, with • for unsaved changes.
    - The open file is remembered (`ScoreFiles`); the text is still autosaved to the
      draft.
    - The whole score's PDF is copied out after each render, for sharing.
  - Still missing for files: `\include` of other files next to an opened file, since
    a document picker gives no folder.
  - Tests: 7 Python tests, plus an instrumented test of chord entry in voice 2, on
    the chord after the `<< >>`, and a respelling, against LilyPond's engraving. The
    file menu was checked on the emulator: save as, save, new, open, share.

- **0.20.0, AndroLily:**
  - The app is now AndroLily, with the permanent id `org.androlily` (the Kotlin
    packages keep their `org.lilyengine` names, which are internal). It has an
    adaptive icon (an eighth note), and other MIDI apps see its device as
    "AndroLily". Downloads are named `androlily-<version>-arm64.apk`.
  - Also in this version: syntax colours in the source editor (`LilyHighlight`), and
    a Transpose dialog for the selection or the whole score. Part of a `\relative`
    passage is transposed with the music after it kept sounding the same.
- **0.21.0, lyric entry:**
  - Lyrics mode in the Score tab: tap a note of the melody and type the verse as
    plain text (words with spaces, `a-part` for syllables, `word_` to hold it, `_`
    alone for a note without a syllable). It is written into the source 0.6 s after
    typing stops and re-engraved. Verses can be added (+ verse), edited and removed.
  - Where it goes (`lilyedit.lyrics_target`, `set_verse`):
    - After a melody without lyrics: `\addlyrics { Twin -- kle … __ }`.
    - In a voice of a `\\` passage: on that voice's own `{ }`, with its
      `\voiceOne`/`\voiceTwo` written in, since `\addlyrics` would otherwise drop it.
    - Existing `\lyricsto` lyrics, inline or in a `\lyricmode` variable (as
      musicxml2ly writes them), are read and edited in place. A named voice's
      further verses go on another `\new Lyrics \lyricsto` line, since `\addlyrics`
      would hide the voice's name.
  - The bar shows how the verse fits: syllables against notes to sing. Ties, the
    notes under a slur after its first, and rests take none (`verse_check`).
  - Tapping a sung word on the score opens its verse with the cursor on the word
    (`verse_at`).
  - Tests: 13 Python tests, an instrumented test (verses for a melody and for voice
    2, checked against LilyPond's engraving with no voice warnings), and the host
    LilyPond compiling the `\lyricsto` and named-voice outputs cleanly.

- **0.22.0, voices, includes, recording:**
  - Voice switcher: in Enter notes, when the selected note's staff has several
    voices at its moment, a button per voice (in its colour) moves the entry point to
    that voice's note sounding then (`ScoreLookup.voicesAt`, in `\\` order).
  - `\include` for scores opened with the file picker. A render that fails on an
    included file offers "Choose its folder"; once granted, the included files (and
    theirs) are copied into a working folder before each render, and LilyPond is
    given that folder with `-I` (`IncludeFolder`). Android won't grant Downloads
    itself, so the score needs a folder of its own.
  - Real-time recording (`Recorder`, `lilyedit.record`):
    - In Enter notes, Record counts in one bar, then takes down what is played while
      the metronome clicks: on the keyboard (woodblock, channel 10) if connected,
      else as a beep.
    - Stop quantizes to 1/16 or 1/8. Keys starting together form chords, gaps of an
      eighth note or more become rests (shorter ones are the note held), and notes
      are split and tied at bar lines (the `\time` in effect and the start moment).
      The notes are spelled for the key, with octaves corrected, and written after
      the selected note.
    - Key releases now carry the MIDI timestamp too.
    - Splitting into voices by register is still to do.
  - Tests: 5 Python tests, and an instrumented recording test through the test
    keyboard at ♩=240, which was timed within 6 ms and came out as
    `c'4 d'4 e'4 <g' b'>2`. Also a voicesAt unit test and an include-parsing unit
    test.

- **0.23.0, new scores and two-hand recording:**
  - File ▸ New… opens a dialog of templates (`lilyedit.new_score`): melody, piano,
    voice and piano, SATB on four staves or on two (named voices), lead sheet
    (ChordNames over the melody), string quartet. It takes the title, composer,
    key, time, tempo and number of bars. The parts are variables with a shared
    `global`, each filled with bars of rests, ready for Fill rests. All seven were
    checked with the host LilyPond.
  - Recording with two hands: when the selected note has a staff below whose music
    lines up there (`ScoreLookup.alignedBelow`), "Two hands" sends notes below middle
    C into that staff, from the same moment. The later place is written first, so
    the earlier offset stays valid.
  - `ScoreLookup.staffOf` now places grobs without a staff position (whole rests)
    on the nearest staff.
  - Tests: 2 Python tests, and an instrumented two-hand recording into the piano
    template, checked staff by staff in LilyPond's engraving.

- **0.24.0, redo, score settings, copy and paste:**
  - Redo: the undo history keeps what was undone until the next edit, and Redo sits
    next to Undo in both bars.
  - File ▸ Score settings: title, subtitle, composer, arranger, lyricist and
    copyright (in the top-level `\header`; a score's own header is left alone),
    paper size and orientation (`#(set-default-paper-size "letterlandscape")`, the
    2.26 form that really turns the page), and staff size
    (`#(set-global-staff-size 18)`). Implemented in `lilyedit.settings` and
    `set_settings`.
  - Copy, Cut and Paste in the edit bar. Copy takes the selected stretch of one voice
    with its first duration written out and its notes' absolute pitches
    (`lilyedit.copy`). Paste inserts it after the selected note anywhere, with
    octave marks corrected so every note sounds as copied, in `\relative` or
    absolute music (`lilyedit.paste`). The fragment also goes on the system
    clipboard.
  - Tests: 6 Python tests, plus instrumented tests: settings turning the page to
    US Letter landscape in the PDF, and voice 2's bar pasted after the melody,
    checked in LilyPond's engraving.

- **0.25.0, changes in the music:**
  - Insert… in the edit bar puts things in at the selected note:
    - a clef, key, time or tempo change, before the note; one of the same kind
      already there is replaced, not doubled (`lilyedit.change`);
    - bar lines (double, final, repeat signs), before or after the note;
    - text above or below (`^"dolce"`, `lilyedit.add_text`);
    - `\repeat volta n { … }` around the selected range (`lilyedit.repeat`).
  - A `\key` has a pitch of its own, so the octave check gives changes' pitches
    their own value and keeps every other note sounding as before.
  - The dialogs' choice chips wrap onto more lines, so every option is visible on a
    narrow screen.
  - Tests: 3 Python tests, plus an instrumented test (key, clef, final bar, text and
    repeat engrave cleanly, with an extra key signature and clef, and the notes as
    before).

- **0.26.0, tuplets, grace notes, octave lines:**
  - Insert… with a range selected also offers:
    - tuplets: `\tuplet 3/2 { … }`, plus duplets, quintuplets and sextuplets;
    - grace notes: `\acciaccatura`, `\appoggiatura`, `\grace`;
    - octave lines: `\ottava #1` … `\ottava #0`, also 8vb and 15ma.
  - All three are `lilyedit.wrap`. It writes out the first wrapped item's duration
    and the duration of the item after, since grace notes would otherwise pass
    theirs on, and keeps the pitches.
  - Tests: 3 Python tests, plus an instrumented test: a triplet that takes one beat
    (the note after starts on beat 3, from LilyPond's moments), an 8va line, and an
    acciaccatura, all engraved with no warnings.

- **0.31.1:** playback sounds on the tablet by default, also with a MIDI keyboard connected (the sound button still switches to it).
- **0.31.0, tabs and coding keys:**
  - Several documents open at once, as tabs under the title band (`Documents.kt`).
    Each tab keeps its own draft, file record and undo history. A tab without a file
    is named by its score's title. × closes a tab (asking first if it has unsaved
    changes), and + opens a new one. Open…, New… and the samples open in a new tab
    unless the current one is untouched. The single draft from before becomes the
    first tab.
  - While the source is typed with the on-screen keyboard, two rows of coding keys
    sit above it (\\ { } << >> < > [ ] ( ) ~ ' , | / " # % ^ _ - = ! . * : $ and
    the cursor arrows), in place of the keypad.
  - Add… moved to the start of the keypad's tab row. The keypad's buttons are
    smaller (44 instead of 60), with tighter spacing and a Button size setting.
  - Delete left the top bar (Cut does it, keeping a copy). It is still in the
    catalog, to put on a page.
  - The File button just says File; the tab shows the name.
- **0.30.0, a look of its own** (`Theme.kt`):
  - Light and dark colour schemes (slate blue, warm neutrals), following the system
    or set in Settings. Colours the scheme doesn't name are in `AppColors`.
  - The score's paper is never inverted: warm paper in light mode, gray in dark,
    with the music black on both. Pages render onto a transparent bitmap
    (`ScoreDocument.render(…, background)`) and `ScoreView` lays them on `paper`
    over `desk`.
  - A narrow band along the top reads ANDROLILY and holds ⚙ Settings (appearance,
    keypad, diagnostics). It runs under the status bar.
  - The source editor, note-entry strip, warnings and syntax colours follow the
    theme. Selected buttons use the scheme's on-primary colour, so they stay
    readable in dark mode.
- **0.29.0, the keypad holds only notation:**
  - One set of note values (and the dot) for both modes: in Notes mode they are for
    new notes, otherwise they change the selected notes. The Edit and Notes pages
    became one Notes page, and no command is on two default pages.
  - Cut, Copy, Paste and Delete are in the top bar, by Undo and Redo. (Delete leaves
    the clipboard alone; Cut replaces it.) Add… is in the top bar too.
  - Entry settings and recording (Fill, Chord, the piano, tempo, grid, two hands,
    Record) are in a strip shown only in Notes mode, not on the keypad, and not in
    Add…, which lists only notation.
  - The top bar wraps onto a second row on narrow screens instead of scrolling.
  - The Test, Benchmark and MIDI tabs are gone: File ▸ Diagnostics… opens them over
    the score, with Back to return.
  - Keypad layouts saved by 0.28 are replaced by the new default pages once.
- **0.28.0, a keypad instead of rows of buttons** (after looking at how MuseScore,
  Sibelius and Dorico for iPad, StaffPad, Finale, Harmony Assistant and Denemo handle
  hundreds of commands):
  - One catalog of every command (`Command` in `Keypad.kt`): id, name (its tooltip),
    group, glyph, and when it applies.
  - A fixed grid of pages (Edit, Notes, Marks, Lines, Score). Buttons that don't apply
    are dimmed, not removed, so nothing moves.
    - Columns and rows are set in Keypad settings.
    - Docks along the bottom when the window is taller than wide and at the side when
      wider (or always bottom or side). Nothing assumes landscape.
  - Pages follow along (Notes mode → Notes, a range → Lines). On by default; the pin
    stops it.
  - Customizable: Arrange buttons (tap a slot, pick from the searchable catalog), add,
    rename and remove pages, presets (All buttons, Minimal). Saved between sessions.
  - Floating windows: any page tears off (⧉) into a window that drags by its title,
    snaps to the edges, collapses, docks again, and can be see-through. Positions are
    kept separately for tall and wide screens.
  - Add…: the whole catalog as tiles, searchable. Replaces the "⋮" menu; the Insert
    dialog opens at the kind chosen (clef, key…).
  - Every button has a tooltip: on pen or mouse hover, and on a long press.
  - Top bar: Undo, Redo, File, Edit | Notes | Lyrics, Play, keypad and source
    toggles. The LilyPond source is hidden until asked for. The app opens on the score.
  - More marks are reachable: portato, turn, prall, mordent, bowings, more dynamics.
- **0.27.0, fixes from the third tablet test:**
  - A tap on the staff now goes where it is tapped, not after the selected note
    (`ScoreLookup.entryPlace`).
    - Within the tapped bar, the note goes after the last note whose left edge is
      left of the tap, into a rest there (filled), or before the bar's first note.
    - The voice is the selected note's if it has music in that bar; otherwise the
      voice whose notes there are nearest the tap.
  - Staves with only rests (a new score) had no note to read the clef from, so taps
    did nothing. The score map now records each Clef's glyph and position and each
    ClefModifier's octave, and `pitchAt` falls back to the clef in effect (G: g',
    F: f, C: c'; the 8 of treble_8 moves it an octave).
  - Slurs and hairpins over a selection in two voices (e.g. soprano and alto on
    their own staves) grouped unnamed voices together. They are now grouped by
    voice context. Repeat, tuplets, grace notes and octave lines over several voices
    are done voice by voice.
  - 1st and 2nd endings: `lilyedit.first_ending` turns the end of a repeat into
    `\alternative { { … } }`, and `next_ending` adds the notes right after it as the
    next ending.
  - Lyrics for a voice in a `\\` passage went under the bottom staff. They now get
    `\addlyrics \with { alignBelowContext = "…" }`, and the staff is given a name
    (its variable's) if it had none.
  - Text can be bold and italic (`^\markup { \bold \italic "…" }`).
  - The bars were redesigned:
    - compact square tools, with note values drawn as notes;
    - Undo and Redo as icons, always at the start of the top row;
    - Delete, Copy, Cut and Paste as icons;
    - Insert, Transpose and Add/Remove voice in a "⋮" menu;
    - short glyphs for the marks, and dynamics in bold italic.
  - Tests: 3 Python tests, an entryPlace unit test, and an instrumented test (a tap
    on an empty new choir score fills that bar's rest, and the clefs give b' b' b d
    for the four middle lines).

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
