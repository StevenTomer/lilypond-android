"""Structural edits on LilyPond source text, built on python-ly.

Every function takes the whole source text and returns the whole new text;
the Kotlin side reduces that to a minimal edit. Positions are Python string
indices (Unicode code points), the same as LilyPond's own character counts.

The edits keep the rest of the music sounding the same. Changing a note's
pitch in \\relative music shifts the octave of the notes after it, so the
octave marks of the following notes are corrected; giving a note a new
duration would change the next note too when that one has no duration of
its own, so it gets its old duration written out.

python-ly's rel2abs is the reference for what each note's absolute pitch
is: edits are made, the absolute pitches recomputed, and octave marks
corrected until every note sounds where it should.
"""

import json
from fractions import Fraction

import ly.document
import ly.lex
import ly.lex.lilypond
import ly.pitch
import ly.pitch.rel2abs
import ly.pitch.transpose
import ly.rhythm

# --------------------------------------------------------------------------
# Notes and their absolute pitches


class Note(object):
    __slots__ = ("pos", "end", "octave_pos", "octave_end", "note", "alter",
                 "written_octave", "octave")

    def key(self):
        return (self.note, self.alter, self.octave)


def _pitches(text):
    """All pitches in source order, as written (octave = written marks)."""
    doc = ly.document.Document(text)
    source = ly.document.Source(ly.document.Cursor(doc), True, tokens_with_position=True)
    result = []
    for p in ly.pitch.PitchIterator(source).pitches():
        if not isinstance(p, ly.pitch.Pitch):
            continue
        n = Note()
        n.pos = p.note_token.pos
        n.note = p.note
        n.alter = p.alter
        n.written_octave = p.octave
        n.octave = p.octave
        if p.octave_token is not None:
            n.octave_pos, n.octave_end = p.octave_token.pos, p.octave_token.end
        else:
            n.octave_pos = n.octave_end = p.note_token.end
        last = p.octavecheck_token or p.accidental_token or p.octave_token or p.note_token
        n.end = last.end
        result.append(n)
    return result


def notes(text):
    """Every pitch in the text with its absolute octave (python-ly numbering:
    c' is octave 1). Pitches in \\relative music are made absolute the way
    LilyPond does (2.18 and later: no start pitch means the first note is
    absolute)."""
    result = _pitches(text)
    by_pos = {n.pos: n for n in result}
    absolute = {}

    real_write = ly.pitch.PitchIterator.write

    def record(self, pitch, language=None):
        absolute[pitch.note_token.pos] = pitch.octave

    ly.pitch.PitchIterator.write = record
    try:
        doc = ly.document.Document(text)
        ly.pitch.rel2abs.rel2abs(ly.document.Cursor(doc), first_pitch_absolute=True)
    finally:
        ly.pitch.PitchIterator.write = real_write
    for pos, octave in absolute.items():
        if pos in by_pos:
            by_pos[pos].octave = octave
    return result


def _octave_string(octave):
    return "'" * octave if octave > 0 else "," * -octave


def _note_index(notes_, pos):
    for i, n in enumerate(notes_):
        if n.pos <= pos < max(n.end, n.pos + 1):
            return i
    raise ValueError("no note at position %d" % pos)


def _fix_octaves(text, desired, max_rounds=200):
    """Corrects written octave marks, in source order, until every note's
    absolute pitch equals `desired` (a list of (note, alter, octave) for
    the same notes in the same order)."""
    for _ in range(max_rounds):
        current = notes(text)
        if len(current) != len(desired):
            raise ValueError("the number of notes changed")
        for n, want in zip(current, desired):
            if n.key() == want:
                continue
            if (n.note, n.alter) != want[:2]:
                raise ValueError("unexpected pitch change at %d" % n.pos)
            marks = _octave_string(n.written_octave + want[2] - n.octave)
            text = text[:n.octave_pos] + marks + text[n.octave_end:]
            break
        else:
            return text
    raise ValueError("octaves did not settle")


def _language_writer(text):
    return ly.pitch.pitchWriter(_language(text))


def _language(text):
    doc = ly.document.Document(text)
    source = ly.document.Source(ly.document.Cursor(doc), True, tokens_with_position=True)
    it = ly.pitch.PitchIterator(source)
    for _ in it.tokens():
        pass
    return it.language


def set_pitch(text, pos, note, alter, octave):
    """Gives the note at `pos` a new absolute pitch (note 0-6 = c-b, alter in
    whole tones, e.g. 0.5 for a sharp, octave as in notes()). Everything
    else keeps sounding the same."""
    before = notes(text)
    i = _note_index(before, pos)
    target = before[i]
    desired = [n.key() for n in before]
    desired[i] = (note, alter, octave)
    name = _language_writer(text)(note, alter)
    # The pitch name and accidental mark (! or ?) are replaced; the octave
    # marks are set right by _fix_octaves.
    marks = _octave_string(target.written_octave)
    text = text[:target.pos] + name + marks + text[target.end:]
    return _fix_octaves(text, desired)


# Semitones above the tonic of each scale step, per mode.
_MODES = {
    "major": [0, 2, 4, 5, 7, 9, 11], "ionian": [0, 2, 4, 5, 7, 9, 11],
    "minor": [0, 2, 3, 5, 7, 8, 10], "aeolian": [0, 2, 3, 5, 7, 8, 10],
    "dorian": [0, 2, 3, 5, 7, 9, 10], "phrygian": [0, 1, 3, 5, 7, 8, 10],
    "lydian": [0, 2, 4, 6, 7, 9, 11], "mixolydian": [0, 2, 4, 5, 7, 9, 10],
    "locrian": [0, 1, 3, 5, 6, 8, 10],
}
_NATURAL = [0, 2, 4, 5, 7, 9, 11]  # c d e f g a b


def key_at(text, pos):
    """The key signature in effect at `pos`: (tonic note, tonic alter, mode)
    from the last \\key before it in the text, or C major. (A \\key in a
    variable defined earlier, like `global`, counts, since it comes first
    in the text.)"""
    doc = ly.document.Document(text)
    source = ly.document.Source(ly.document.Cursor(doc), True, tokens_with_position=True)
    key = (0, 0, "major")
    state, tonic = None, None
    for t in ly.pitch.PitchIterator(source).pitches():
        is_pitch = isinstance(t, ly.pitch.Pitch)
        if (t.note_token.pos if is_pitch else t.pos) >= pos:
            break
        if not is_pitch and isinstance(t, (ly.lex.Space, ly.lex.Comment)):
            continue
        if not is_pitch and t == "\\key":
            state = "tonic"
        elif state == "tonic" and is_pitch:
            tonic, state = t, "mode"
        elif state == "mode":
            mode = str(t).lstrip("\\")
            if mode in _MODES:
                key = (tonic.note, tonic.alter, mode)
            state = None
        else:
            state = None
    return key


def scale_alter(key, note):
    """The alteration (in whole tones) that `note` (0-6) has in `key`."""
    tonic, tonic_alter, mode = key
    tonic_semitone = _NATURAL[tonic] + tonic_alter * 2
    wanted = int(round(tonic_semitone + _MODES[mode][(note - tonic) % 7])) % 12
    d = (wanted - _NATURAL[note]) % 12
    if d > 6:
        d -= 12
    return Fraction(d, 2)


def step(text, pos, by):
    """Moves the note at `pos` up (by > 0) or down by scale steps, spelled
    as the key signature in effect there has it: in G major, e goes up to
    fis."""
    before = notes(text)
    n = before[_note_index(before, pos)]
    s = n.note + by
    octave = n.octave + s // 7
    s %= 7
    return set_pitch(text, pos, s, scale_alter(key_at(text, pos), s), octave)


def transpose(text, start, end, from_pitch, to_pitch):
    """Transposes the music between start and end (whole text if end < 0)
    by the interval from_pitch -> to_pitch, e.g. "c" -> "d"."""
    doc = ly.document.Document(text)
    cursor = ly.document.Cursor(doc, start, None if end < 0 else end)
    reader = ly.pitch.pitchReader(_language(text))
    def parse(s):
        n = len(s.rstrip("',"))
        note, alter = reader(s[:n])
        octave = s[n:].count("'") - s[n:].count(",")
        return ly.pitch.Pitch(note, alter, octave)
    t = ly.pitch.transpose.Transposer(parse(from_pitch), parse(to_pitch))
    ly.pitch.transpose.transpose(cursor, t)
    return doc.plaintext()


def rel2abs(text):
    doc = ly.document.Document(text)
    ly.pitch.rel2abs.rel2abs(ly.document.Cursor(doc), first_pitch_absolute=True)
    return doc.plaintext()


# --------------------------------------------------------------------------
# Durations


def _items(text):
    """Music items (notes, chords, rests, skips) with their written and
    effective durations, in source order."""
    doc = ly.document.Document(text)
    items = list(ly.rhythm.music_items(ly.document.Cursor(doc), command=False, chord=False))
    current = "4"
    result = []
    for it in items:
        written = "".join(it.dur_tokens) if it.dur_tokens else None
        if written:
            current = written
        result.append((it, written, current))
    return result


def _item_index(text, items, pos):
    """The item for a source position; a position inside a chord <...>
    means the chord."""
    for k, (it, _, _) in enumerate(items):
        if it.pos <= pos < it.end:
            return k
        if it.pos > pos:
            # A note inside a chord: the chord's item starts at its '>'.
            if text[it.pos] == ">" and text.rfind("<", 0, it.pos) <= pos:
                return k
            break
    raise ValueError("nothing with a duration at position %d" % pos)


def set_duration(text, pos, duration):
    """Gives the note, chord or rest at `pos` a new duration, e.g. "8." The
    next item keeps its duration: if it had none written, its old one is
    written out."""
    items = _items(text)
    k = _item_index(text, items, pos)
    it, written, effective = items[k]
    edits = []
    if k + 1 < len(items):
        nxt, nxt_written, nxt_effective = items[k + 1]
        if nxt_written is None and nxt_effective != duration:
            edits.append((nxt.insert_pos, nxt.insert_pos, nxt_effective))
    if it.dur_tokens:
        edits.append((it.dur_tokens[0].pos, it.dur_tokens[-1].end, duration))
    else:
        edits.append((it.insert_pos, it.insert_pos, duration))
    for start, end, s in sorted(edits, reverse=True):
        text = text[:start] + s + text[end:]
    return text


def delete(text, pos):
    """Removes the note, chord or rest at `pos` (with its duration and any
    articulations attached to it), keeping the rest of the music the same:
    the next item keeps its duration and the following notes their octaves.
    A note inside a chord is removed from the chord only."""
    before = notes(text)
    items = _items(text)
    i = None
    try:
        i = _note_index(before, pos)
    except ValueError:
        pass
    k = _item_index(text, items, pos)
    it, written, effective = items[k]
    in_chord = text[it.pos] == ">" and i is not None and before[i].pos < it.pos
    if in_chord:
        n = before[i]
        start, end = n.pos, n.end
        while end < len(text) and text[end] in " \t":
            end += 1
        removed = [i]
    else:
        start = text.rfind("<", 0, it.pos) if text[it.pos] == ">" else it.pos
        end = it.end
        # attached articulations, dynamics, slurs, ties: up to the next space
        while end < len(text) and not text[end].isspace() and text[end] not in "}>|":
            end += 1
        removed = [j for j, n in enumerate(before) if start <= n.pos < end]
        # the following item inherits our duration if it had none
        if k + 1 < len(items):
            nxt, nxt_written, nxt_effective = items[k + 1]
            if nxt_written is None and nxt_effective != (k > 0 and items[k - 1][2] or "4"):
                text = text[:nxt.insert_pos] + nxt_effective + text[nxt.insert_pos:]
        # and one space goes too
        while end < len(text) and text[end] in " \t":
            end += 1
    desired = [n.key() for j, n in enumerate(before) if j not in removed]
    text = text[:start] + text[end:]
    return _fix_octaves(text, desired)


# --------------------------------------------------------------------------
# Entry point for Kotlin: one JSON request in, one JSON reply out.


def call(request_json):
    req = json.loads(request_json)
    op = req["op"]
    text = req["text"]
    try:
        if op == "set_pitch":
            out = set_pitch(text, req["pos"], req["note"], req["alter"], req["octave"])
        elif op == "step":
            out = step(text, req["pos"], req["by"])
        elif op == "set_duration":
            out = set_duration(text, req["pos"], req["duration"])
        elif op == "delete":
            out = delete(text, req["pos"])
        elif op == "transpose":
            out = transpose(text, req.get("start", 0), req.get("end", -1), req["from"], req["to"])
        elif op == "rel2abs":
            out = rel2abs(text)
        elif op == "notes":
            return json.dumps({"notes": [
                {"pos": n.pos, "end": n.end, "note": n.note, "alter": float(n.alter), "octave": n.octave}
                for n in notes(text)]})
        else:
            return json.dumps({"error": "unknown op " + op})
        return json.dumps({"text": out})
    except Exception as e:  # reported to the caller, not thrown across the bridge
        return json.dumps({"error": "%s: %s" % (type(e).__name__, e)})
