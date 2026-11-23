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
import re
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
    whole = start <= 0 and end < 0
    if not whole:
        # Part of the music: the notes in it move by the interval, all others
        # must sound as before (in \relative, the note after the part would
        # otherwise follow the moved ones).
        stop = len(text) if end < 0 else end
        desired = []
        for n in notes(text):
            if start <= n.pos < stop:
                p = ly.pitch.Pitch(n.note, n.alter, n.octave)
                t.transpose(p)
                desired.append((p.note, p.alter, p.octave))
            else:
                desired.append(n.key())
    ly.pitch.transpose.transpose(cursor, t)
    out = doc.plaintext()
    return out if whole else _fix_octaves(out, desired)


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


def _item_end(text, it):
    """The end of an item with what is attached to it (articulations,
    dynamics, slurs, ties): up to the next space."""
    end = it.end
    while True:
        while end < len(text) and not text[end].isspace() and text[end] not in "}|":
            # A '>' ends a chord, unless it is an accent (->) or a decrescendo (\\>).
            if text[end] == ">" and text[end - 1] not in "-^_\\":
                break
            end += 1
        # Marks written after a space still belong to the note: "b4 -." (as
        # musicxml2ly writes them). A direction (- ^ _) followed by something
        # starts one.
        j = end
        while j < len(text) and text[j] in " \t":
            j += 1
        if j + 1 < len(text) and text[j] in "-^_" and not text[j + 1].isspace() and j > end:
            end = j
            continue
        return end


def _item_start(text, it):
    """Where an item starts: a chord's item is at its '>'."""
    return text.rfind("<", 0, it.pos) if text[it.pos] == ">" else it.pos


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
        end = _item_end(text, it)
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
# Marks: articulations, dynamics, slurs, hairpins, ties

# What each mark is written as; for articulations, what it is when written
# after a direction (- ^ _), so -. ^. and _. are all staccato.
_ARTICULATIONS = {
    "staccato": ".", "accent": ">", "tenuto": "-", "marcato": "^",
    "staccatissimo": "!", "portato": "_",
    "fermata": "\\fermata", "trill": "\\trill", "turn": "\\turn",
    "prall": "\\prall", "mordent": "\\mordent", "upbow": "\\upbow", "downbow": "\\downbow",
}
_DYNAMICS = ["ppp", "pp", "p", "mp", "mf", "f", "ff", "fff", "fp", "sf", "sfz", "sfp", "rfz"]
# Spanner starts and ends, and ties, as written.
_SPANS = {"slur-start": "(", "slur-end": ")", "cresc": "\\<", "decresc": "\\>",
          "hairpin-end": "\\!", "tie": "~"}
# With a dynamic, a hairpin ends: \! isn't needed (and is removed with \< \>).
_HAIRPINS = ("\\<", "\\>")


def _post_events(s):
    """Splits what follows an item's duration (e.g. "-.\\p(") into
    (start, end, direction, body) tokens; body is e.g. "." or "\\p" or "("."""
    out = []
    i = 0
    while i < len(s):
        start = i
        direction = None
        if s[i] in "-^_" and i + 1 < len(s):
            direction = s[i]
            i += 1
        if s[i] == "\\":
            j = i + 1
            if j < len(s) and s[j].isalpha():
                while j < len(s) and s[j].isalpha():
                    j += 1
            else:
                j += 1  # \< \> \! \( \)
            body = s[i:j]
            i = j
        elif s[i] == '"':
            j = s.find('"', i + 1)
            j = len(s) if j < 0 else j + 1
            body = s[i:j]
            i = j
        elif direction is not None and s[i].isdigit():  # fingering -1
            j = i
            while j < len(s) and s[j].isdigit():
                j += 1
            body = s[i:j]
            i = j
        else:
            body = s[i]
            i += 1
        out.append((start, i, direction, body))
    return out


def _marks_start(text, it):
    """Where the marks attached to an item start: after its duration (python-ly
    counts a tie as part of the item)."""
    if it.dur_tokens:
        return it.dur_tokens[-1].end
    return it.end - 1 if text[it.end - 1] == "~" else it.end


def marks(text, pos):
    """The marks on the note, chord or rest at `pos`, as names (see toggle)."""
    items = _items(text)
    it = items[_item_index(text, items, pos)][0]
    names = []
    for _, _, direction, body in _post_events(text[_marks_start(text, it):_item_end(text, it)]):
        for name, b in _ARTICULATIONS.items():
            if body == b and (direction is not None or b.startswith("\\")):
                names.append(name)
        if body.startswith("\\") and body[1:] in _DYNAMICS:
            names.append(body[1:])
        for name, b in _SPANS.items():
            if body == b:
                names.append(name)
    return names


def toggle(text, pos, what):
    """Adds the mark `what` to the note, chord or rest at `pos`, or removes
    it if it is there. `what` is an articulation ("staccato", "accent",
    "tenuto", "marcato", "staccatissimo", "portato", "fermata", "trill",
    "turn", "prall", "mordent", "upbow", "downbow"), a dynamic ("p", "mf",
    "sfz"…, which replaces another dynamic there), or "slur-start",
    "slur-end", "cresc", "decresc", "hairpin-end", "tie". A note inside a
    chord means the whole chord. Marks go after what is already attached,
    in the order LilyPond wants: a tie or slur after articulations doesn't
    matter to it, so they are simply added at the end."""
    items = _items(text)
    it = items[_item_index(text, items, pos)][0]
    start, end = _marks_start(text, it), _item_end(text, it)
    events = _post_events(text[start:end])

    def matches(direction, body):
        if what in _ARTICULATIONS:
            b = _ARTICULATIONS[what]
            return body == b and (direction is not None or b.startswith("\\"))
        if what in _DYNAMICS:
            return body == "\\" + what
        return body == _SPANS[what]

    if what not in _ARTICULATIONS and what not in _DYNAMICS and what not in _SPANS:
        raise ValueError("unknown mark " + what)
    present = [e for e in events if matches(e[2], e[3])]
    remove = list(present)
    add = None
    if not present:
        if what in _ARTICULATIONS:
            b = _ARTICULATIONS[what]
            add = b if b.startswith("\\") else "-" + b
        elif what in _DYNAMICS:
            add = "\\" + what
            # one dynamic per note: another one is replaced
            remove = [e for e in events if e[3].startswith("\\") and e[3][1:] in _DYNAMICS]
        else:
            add = _SPANS[what]
            if what in ("cresc", "decresc"):  # one hairpin start per note
                remove = [e for e in events if e[3] in _HAIRPINS]
    attached = text[start:end]
    for s_, e_, _, _ in sorted(remove, reverse=True):
        attached = attached[:s_] + attached[e_:]
    if remove:  # marks written after spaces leave spaces behind
        attached = re.sub(r"[ \t]+", " ", attached).rstrip(" \t")
    if add:
        attached += add
    return text[:start] + attached + text[end:]


# --------------------------------------------------------------------------
# Edits on a range: several notes, chords and rests at once

def set_alter(text, pos, alter):
    """Gives the note at `pos` a new accidental (alter in whole tones),
    keeping its note name and octave."""
    n = notes(text)[_note_index(notes(text), pos)]
    return set_pitch(text, pos, n.note, alter, n.octave)


def _identify(text, positions):
    """What each position is, so it can be found again after an edit that
    adds or removes no notes: ("n", note index) or ("i", item index)."""
    notes_ = notes(text)
    items = _items(text)
    ids = []
    for p in positions:
        try:
            ids.append(("n", _note_index(notes_, p)))
        except ValueError:
            ids.append(("i", _item_index(text, items, p)))
    return ids


def _locate(text, ids):
    notes_ = notes(text)
    items = _items(text)
    return [notes_[k].pos if kind == "n" else _item_start(text, items[k][0]) for kind, k in ids]


def _item_starts(text, positions):
    """The items (notes, chords, rests) at `positions`, each once, as
    (start, item index, item, effective duration), last first."""
    items = _items(text)
    found = {}
    for p in positions:
        k = _item_index(text, items, p)
        it, _, effective = items[k]
        found[k] = (_item_start(text, it), k, it, effective)
    return sorted(found.values(), reverse=True)


def _drop_repeated_durations(text, indices, before):
    """Removes the written duration of the items at `indices` where it is
    the one in effect anyway (c8 e8 g8 -> c8 e g), last first; also of the
    item after each, if it had none written in `before` (the items before
    the edit)."""
    items = _items(text)
    extra = [k + 1 for k in indices if k + 1 < len(before) and before[k + 1][1] is None]
    for k in sorted(set(indices) | set(extra), reverse=True):
        it, written, _ = items[k]
        if k > 0 and written is not None and written == items[k - 1][2] and it.dur_tokens:
            text = text[:it.dur_tokens[0].pos] + text[it.dur_tokens[-1].end:]
    return text


def multi(text, positions, op, arg=None):
    """Applies an edit to every note, chord or rest at `positions` (from a
    selected range) and returns (text, new positions of the same items).
    Edits go from the last item to the first, so the earlier positions stay
    valid, and each keeps the rest of the music sounding as before.

    op: "step" (arg: scale steps), "alter" (arg: whole tones; rests are
    skipped), "duration" (arg: e.g. "8."), "dot" (dots every item, or takes
    the dot off if all have one), "toggle" (arg: a mark; added to every item,
    or taken off if all have it), "delete" (new positions: none)."""
    ids = _identify(text, positions)
    if op in ("step", "alter", "respell"):
        notes_ = notes(text)
        targets = sorted({notes_[k].pos for kind, k in ids if kind == "n"}, reverse=True)
        for p in targets:
            if op == "step":
                text = step(text, p, arg)
            elif op == "alter":
                text = set_alter(text, p, arg)
            else:
                try:
                    text = respell(text, p)
                except ValueError:
                    pass  # d, g, a have no simple other spelling: left as they are
    elif op == "duration":
        original = _items(text)
        found = _item_starts(text, positions)
        for start, _, _, _ in found:
            text = set_duration(text, start, arg)
        text = _drop_repeated_durations(text, [k for _, k, _, _ in found], original)
    elif op == "dot":
        original = _items(text)
        found = _item_starts(text, positions)
        all_dotted = all(d.endswith(".") for _, _, _, d in found)
        for start, _, _, d in found:
            if all_dotted:
                text = set_duration(text, start, d.rstrip("."))
            elif not d.endswith("."):
                text = set_duration(text, start, d + ".")
        text = _drop_repeated_durations(text, [k for _, k, _, _ in found], original)
    elif op == "toggle":
        found = _item_starts(text, positions)
        all_have = all(arg in marks(text, start) for start, _, _, _ in found)
        for start, _, _, _ in found:
            if all_have or arg not in marks(text, start):
                text = toggle(text, start, arg)
    elif op == "delete":
        for start, _, _, _ in _item_starts(text, positions):
            text = delete(text, start)
        return text, []
    else:
        raise ValueError("unknown range edit " + op)
    return text, _locate(text, ids)


_SPAN_MARKS = {"slur": ("slur-start", "slur-end"), "cresc": ("cresc", "hairpin-end"),
               "decresc": ("decresc", "hairpin-end")}


def span(text, pairs, kind, positions=()):
    """Draws a slur or hairpin ("slur", "cresc", "decresc") over each
    (first, last) pair of positions, e.g. one pair per voice of a selected
    range; if every pair has it already, takes them off. Returns (text, new
    positions of `positions`)."""
    begin, end = _SPAN_MARKS[kind]
    ids = _identify(text, positions)
    items = _items(text)
    edits = []
    for first, last in pairs:
        a = _item_index(text, items, first)
        b = _item_index(text, items, last)
        if a == b:
            raise ValueError("a slur or hairpin needs two notes or more in a voice")
        a, b = min(a, b), max(a, b)
        edits.append((_item_start(text, items[a][0]), begin))
        edits.append((_item_start(text, items[b][0]), end))
    all_have = all(m in marks(text, p) for p, m in edits)
    for p, m in sorted(edits, reverse=True):
        if all_have or m not in marks(text, p):
            text = toggle(text, p, m)
    return text, _locate(text, ids)


# --------------------------------------------------------------------------
# Note entry (from a MIDI keyboard or an on-screen piano)

# Positions on the line of fifths (C = 0, G = 1, F = -1) of the natural notes.
_FIFTHS_OF_STEP = [0, 2, 4, -1, 1, 3, 5]  # c d e f g a b
_STEP_OF_FIFTHS = {f: s for s, f in enumerate(_FIFTHS_OF_STEP)}
# How many fifths each mode's tonic is above the major key with the same
# signature (A minor: three above C).
_MODE_FIFTHS = {"major": 0, "ionian": 0, "minor": 3, "aeolian": 3, "dorian": 2,
                "phrygian": 4, "lydian": -1, "mixolydian": 1, "locrian": 5}


def key_fifths(key):
    """A key's signature as a number of sharps (negative: flats)."""
    tonic, alter, mode = key
    return _FIFTHS_OF_STEP[tonic] + int(round(alter * 2)) * 7 - _MODE_FIFTHS[mode]


def spell(midi_key, key):
    """The (note, alter, octave) to write for a MIDI key number in `key`
    (as key_at gives it): the notes of the scale as the signature has them,
    the others as the nearest accidentals around the key, e.g. 61 is cis in
    G major and des in F major; in minor keys the raised leading tone
    (gis in A minor). MIDI 60 is c' (octave 1)."""
    fifths = key_fifths(key)
    # Twelve consecutive fifths around the key: in C major, as es bes f c
    # g d a e b fis cis; minor keys shift by one so they get the leading tone.
    low = fifths - (3 if key[2] in ("minor", "aeolian") else 4)
    pc = midi_key % 12
    p = next(p for p in range(low, low + 12) if (7 * p) % 12 == pc)
    sharps = (p + 1) // 7
    note = _STEP_OF_FIFTHS[p - 7 * sharps]
    octave = (midi_key - _NATURAL[note] - sharps) // 12 - 4
    return note, Fraction(sharps, 2), octave


def insert(text, pos, keys, duration=None, after=False):
    """Inserts a note (one MIDI key in `keys`), a chord (several) or a rest
    (none) at `pos`, with `duration` (e.g. "8."; None: the current one).
    With `after`, `pos` is on an item (note, chord, rest) and the new one
    goes after it, past its articulations and ties; otherwise a `pos`
    inside an item means after it. Pitches are spelled for the key at that
    point and given octave marks that make them sound at the keys' pitch,
    in \\relative music too; the music after keeps its pitches and
    durations. Returns (text, start of the new item)."""
    items = _items(text)
    before = notes(text)
    if after:
        pos = _item_end(text, items[_item_index(text, items, pos)][0])
    else:
        for it, _, _ in items:
            if _item_start(text, it) < pos < it.end:
                pos = _item_end(text, it)
                break
    previous = [eff for it, _, eff in items if it.pos < pos]
    current = previous[-1] if previous else "4"
    following = [(it, w, eff) for it, w, eff in items if it.pos >= pos]
    # At the start of a voice the duration is written even if it is the one
    # carried over (from the other voice, in reading order): clearer to read.
    starts_voice = re.search(r"(\{|<<|\\\\)\s*$", text[:pos]) is not None
    write = duration if duration and (duration != current or not previous or starts_voice) else ""
    new_current = duration or current

    key = key_at(text, pos)
    spelled = [spell(k, key) for k in sorted(set(keys))]
    name = _language_writer(text)
    names = [name(n, a) for n, a, _ in spelled]
    if not names:
        item = "r"
    elif len(names) == 1:
        item = names[0]
    else:
        item = "<" + " ".join(names) + ">"
    item += write

    edits = []
    if following:
        nxt, nxt_written, nxt_effective = following[0]
        if nxt_written is None and nxt_effective != new_current:
            edits.append((nxt.insert_pos, nxt_effective))
    pre = "" if pos == 0 or text[pos - 1].isspace() else " "
    post = "" if pos >= len(text) or text[pos].isspace() else " "
    edits.append((pos, pre + item + post))
    for at, s in sorted(edits, reverse=True):
        text = text[:at] + s + text[at:]

    desired = ([n.key() for n in before if n.pos < pos]
               + [(n, a, o) for n, a, o in spelled]
               + [n.key() for n in before if n.pos >= pos])
    return _fix_octaves(text, desired), pos + len(pre)


# --------------------------------------------------------------------------
# Chords and spelling

def add_to_chord(text, pos, keys):
    """Adds notes (MIDI keys, spelled for the key there) to the note or
    chord at `pos`: c4 becomes <c e>4, <c e>4 becomes <c e g>4. Everything
    else keeps sounding the same. Returns (text, start of the chord)."""
    items = _items(text)
    it = items[_item_index(text, items, pos)][0]
    start = _item_start(text, it)
    if _is_rest(text, it):
        raise ValueError("a rest can't take chord notes")
    before = notes(text)
    key = key_at(text, pos)
    spelled = [spell(k, key) for k in sorted(set(keys))]
    name = _language_writer(text)
    names = " ".join(name(n, a) for n, a, _ in spelled)
    if text[start] == "<":  # a chord: the new notes go in before its '>'
        close = it.pos if text[it.pos] == ">" else text.index(">", start)
        at = close
        text = text[:at] + " " + names + text[at:]
        new_start = start
    else:  # a single note: it becomes a chord
        n = before[_note_index(before, start)]
        text = text[:n.pos] + "<" + text[n.pos:n.end] + " " + names + ">" + text[n.end:]
        at = n.end + 1  # where the new names begin, after the "<" was added
        new_start = start
    inserted = len([x for x in before if x.pos < (at if text[start] != "<" else at)])
    # the new notes come right after the chord's existing ones, in source order
    desired = ([x.key() for x in before[:inserted]] + [(n, a, o) for n, a, o in spelled]
               + [x.key() for x in before[inserted:]])
    return _fix_octaves(text, desired), new_start


def respell(text, pos):
    """Writes the note at `pos` with its other spelling, keeping its pitch:
    cis <-> des, e -> fes, c -> bis, b -> ces, f -> eis."""
    before = notes(text)
    n = before[_note_index(before, pos)]
    semis = [0, 2, 4, 5, 7, 9, 11]
    up_step, down_step = (n.note + 1) % 7, (n.note - 1) % 7
    up_gap = (semis[up_step] - semis[n.note]) % 12   # semitones to the next letter
    down_gap = (semis[n.note] - semis[down_step]) % 12
    candidates = []
    if n.alter >= 0:  # sharps and naturals: the letter above, flatter
        candidates.append((up_step, n.alter - Fraction(up_gap, 2), n.octave + (1 if n.note == 6 else 0)))
    if n.alter <= 0:  # flats and naturals: the letter below, sharper
        candidates.append((down_step, n.alter + Fraction(down_gap, 2), n.octave - (1 if n.note == 0 else 0)))
    usable = [c for c in candidates if abs(c[1]) <= Fraction(1, 2)]
    if not usable:
        raise ValueError("no simpler other spelling")
    note, alter, octave = usable[0]
    return set_pitch(text, pos, note, alter, octave)


# --------------------------------------------------------------------------
# Voices: adding a second voice to a passage, removing one, filling rests

def _dur_value(d):
    """A written duration as a fraction of a whole note: "4." is 3/8,
    "1*3/4" is 3/4. None if it can't be read."""
    m = re.match(r"^(\\breve|\\longa|\d+)(\.*)(?:\*(\d+)(?:/(\d+))?)?$", d.strip())
    if not m:
        return None
    base = {"\\breve": Fraction(2), "\\longa": Fraction(4)}.get(m.group(1))
    if base is None:
        base = Fraction(1, int(m.group(1)))
    value, add = base, base
    for _ in m.group(2):
        add /= 2
        value += add
    if m.group(3):
        value *= Fraction(int(m.group(3)), int(m.group(4) or 1))
    return value


_PLAIN = [(Fraction(1, 2 ** k), str(2 ** k)) for k in range(7)]  # 1 2 4 … 64


def _durations_for(value):
    """Written durations adding up to `value`, longest first: 3/4 is ["2."],
    5/8 is ["2", "8"]."""
    out = []
    while value > 0:
        for v, d in _PLAIN:
            if v * Fraction(3, 2) <= value:
                out.append(d + ".")
                value -= v * Fraction(3, 2)
                break
            if v <= value:
                out.append(d)
                value -= v
                break
        else:
            raise ValueError("a duration too short to write")
    return out


def _is_rest(text, it):
    start = _item_start(text, it)
    return text[start] in "rs" and (start + 1 >= len(text) or not text[start + 1].isalpha())


def _same_sequence(text, a, b):
    """Whether nothing between offsets a and b opens or closes a sequence or
    a voice."""
    return not re.search(r"[{}]|<<|>>|\\\\", text[a:b])


def _write_duration(text, it, effective):
    """Writes out an item's duration if it has none written (from the right
    end of the text, so earlier offsets stay valid)."""
    if it.dur_tokens:
        return text
    return text[:it.insert_pos] + effective + text[it.insert_pos:]


def add_voice(text, positions):
    """Turns the items at `positions` (one stretch of one voice) into the
    first voice of `<< { … } \\\\ { rests } >>`, the rests as long as the
    stretch, so a second voice can be entered there. Returns (text, offset
    of the first rest)."""
    items = _items(text)
    ks = sorted({_item_index(text, items, p) for p in positions})
    a, b = ks[0], ks[-1]
    start = _item_start(text, items[a][0])
    end = _item_end(text, items[b][0])
    if not _same_sequence(text, start, end):
        raise ValueError("select notes in one voice, not across voices")
    total = Fraction(0)
    for it, _, eff in items[a:b + 1]:
        v = _dur_value(eff)
        if v is None:
            raise ValueError("can't work out the length of " + eff)
        total += v
    before = notes(text)
    # After the << >>, LilyPond carries on with the rests' last duration, so
    # the next item gets its own written out; so does the first in the
    # passage, which would otherwise follow whatever came before it.
    if b + 1 < len(items) and items[b + 1][1] is None:
        text = _write_duration(text, items[b + 1][0], items[b + 1][2])
    passage = text[start:end]
    first = items[a][0]
    if not first.dur_tokens:
        k = first.insert_pos - start
        passage = passage[:k] + items[a][2] + passage[k:]
    rests = " ".join("r" + d for d in _durations_for(total))
    wrapped = "<< { " + passage + " } \\\\ { " + rests + " } >>"
    text = text[:start] + wrapped + text[end:]
    text = _fix_octaves(text, [n.key() for n in before])
    return text, text.index("{ " + rests, start) + 2


def _enclosing_voices(text, pos):
    """The `<< … \\\\ … >>` around `pos`: (start of <<, end of >>, the parts
    as (start, end) offsets of each voice's text between the separators)."""
    depth_stack = []
    i = 0
    found = None
    while i < len(text):
        if text.startswith("<<", i):
            depth_stack.append(i)
            i += 2
            continue
        if text.startswith(">>", i) and depth_stack:
            open_at = depth_stack.pop()
            if open_at <= pos < i and (found is None or open_at > found[0]):
                found = (open_at, i + 2)
            i += 2
            continue
        if text[i] == "%":  # a comment: to the end of the line
            nl = text.find("\n", i)
            i = len(text) if nl < 0 else nl
            continue
        i += 1
    if not found:
        raise ValueError("not in a passage with two voices (<< { } \\\\ { } >>)")
    start, end = found
    parts, depth, brace, last = [], 0, 0, start + 2
    i = start + 2
    while i < end - 2:
        if text.startswith("<<", i):
            depth += 1; i += 2; continue
        if text.startswith(">>", i):
            depth -= 1; i += 2; continue
        if text[i] == "{":
            brace += 1
        elif text[i] == "}":
            brace -= 1
        elif text.startswith("\\\\", i) and depth == 0 and brace == 0:
            parts.append((last, i))
            last = i + 2
            i += 2
            continue
        i += 1
    parts.append((last, end - 2))
    if len(parts) < 2:
        raise ValueError("not in a passage with two voices (<< { } \\\\ { } >>)")
    return start, end, parts


def remove_voice(text, pos):
    """Removes the voice that `pos` is in from the `<< { } \\\\ { } >>` around
    it; with one voice left, its music takes the place of the << >>.
    Returns the text."""
    start, end, parts = _enclosing_voices(text, pos)
    drop = next((k for k, (a, b) in enumerate(parts) if a <= pos < b), None)
    if drop is None:
        raise ValueError("select a note in the voice to remove")
    items = _items(text)
    # Durations that would change: the first item of each voice and the one
    # after the >> get theirs written out (from the right end).
    fixes = []
    for a, b in parts:
        inside = [x for x in items if a <= x[0].pos < b]
        if inside:
            fixes.append(inside[0])
    after = [x for x in items if x[0].pos >= end]
    if after:
        fixes.append(after[0])
    for it, written, eff in sorted(fixes, key=lambda x: x[0].pos, reverse=True):
        if written is None:
            text = _write_duration(text, it, eff)
    start, end, parts = _enclosing_voices(text, start + 2)
    keep = [text[a:b].strip() for k, (a, b) in enumerate(parts) if k != drop]
    dropped_span = parts[drop]
    if len(keep) == 1:
        body = keep[0]
        if body.startswith("{") and body.endswith("}"):
            body = body[1:-1].strip()
        replacement = body
    else:
        replacement = "<< " + " \\\\ ".join(keep) + " >>"
    current = notes(text)
    desired = [n.key() for n in current if not (dropped_span[0] <= n.pos < dropped_span[1])]
    text = text[:start] + replacement + text[end:]
    return _fix_octaves(text, desired)


def keys_for_steps(text, pos, steps):
    """MIDI keys for notes given as (note 0-6, octave; 0 is the octave from
    middle C up) on a staff, with the accidental the key signature at `pos`
    gives them: a tap on the F line in G major is F sharp."""
    key = key_at(text, pos)
    return [60 + 12 * octave + _NATURAL[n] + int(scale_alter(key, n) * 2) for n, octave in steps]


def fill(text, pos, keys, duration):
    """Note entry over rests: the note, chord or rest (empty `keys`) takes
    the place of the rest at `pos`, or of the rest right after the item at
    `pos` in the same voice; what is left of the rest stays as rests.
    Returns (text, start of the new item), or None if there is no rest to
    fill there."""
    items = _items(text)
    k = _item_index(text, items, pos)
    if _is_rest(text, items[k][0]):
        target = k
    elif (k + 1 < len(items) and _is_rest(text, items[k + 1][0])
          and _same_sequence(text, _item_end(text, items[k][0]), _item_start(text, items[k + 1][0]))):
        target = k + 1
    else:
        return None
    rest, _, rest_eff = items[target]
    have, want = _dur_value(rest_eff), _dur_value(duration)
    if have is None or want is None:
        return None
    rest_start = _item_start(text, rest)
    # The new item goes in front of the rest, which then shrinks or goes.
    text, new_start = insert(text, rest_start, keys, duration)
    items = _items(text)
    rest_k = next(j for j, (it, _, _) in enumerate(items) if _item_start(text, it) > new_start and _is_rest(text, it))
    left = have - want
    if left <= 0:
        text = delete(text, _item_start(text, items[rest_k][0]))
    else:
        parts = _durations_for(left)
        rest_pos = _item_start(text, items[rest_k][0])
        text = set_duration(text, rest_pos, parts[0])
        at = rest_pos
        for d in parts[1:]:
            text, at = insert(text, at, [], d, after=True)
    return text, new_start


# --------------------------------------------------------------------------
# Checking that the voices of a << { } \\\\ { } >> passage are equally long

# Music whose length isn't simply the sum of its durations: not checked.
_UNCHECKED = re.compile(r"\\(tuplet|times|grace|acciaccatura|appoggiatura|afterGrace|repeat|scaleDurations|partial|tag|keepWithTag|removeWithTag)\b|<<|\\[a-zA-Z]+\s*=")


def _all_voice_passages(text):
    """Every `<< … \\\\ … >>` in the text, innermost too: (start, end, parts)."""
    found = []
    stack = []
    i = 0
    while i < len(text):
        if text[i] == "%":
            nl = text.find("\n", i)
            i = len(text) if nl < 0 else nl
            continue
        if text[i] == '"':
            j = text.find('"', i + 1)
            i = len(text) if j < 0 else j + 1
            continue
        if text.startswith("<<", i):
            stack.append(i)
            i += 2
            continue
        if text.startswith(">>", i) and stack:
            start = stack.pop()
            try:
                s, e, parts = _enclosing_voices(text, start + 2)
                if s == start:
                    found.append((s, e, parts))
            except ValueError:
                pass
            i += 2
            continue
        i += 1
    return found


def _part_length(text, items, a, b):
    """The length in whole notes of the music between offsets a and b, or
    None if it can't simply be added up."""
    inner = text[a:b].strip()
    if inner.startswith("{") and inner.endswith("}"):
        inner = inner[1:-1]
    if _UNCHECKED.search(inner):
        return None
    total = Fraction(0)
    for it, _, eff in items:
        if a <= it.pos < b:
            v = _dur_value(eff)
            if v is None:
                return None
            total += v
    return total


def voice_lengths(text):
    """The `<< { } \\\\ { } >>` passages whose voices aren't all equally long:
    a list of (start, end, [length of each voice in whole notes]). Passages
    with tuplets, grace notes, repeats or nested << >> aren't checked."""
    items = _items(text)
    out = []
    for start, end, parts in _all_voice_passages(text):
        lengths = [_part_length(text, items, a, b) for a, b in parts]
        if None in lengths or len(set(lengths)) <= 1:
            continue
        out.append((start, end, lengths))
    return out


def pad_voices(text, pos):
    """Fills the shorter voices of the passage at `pos` with rests at their
    end, up to the longest. Returns the text."""
    start, end, parts = _enclosing_voices(text, pos)
    items = _items(text)
    lengths = [_part_length(text, items, a, b) for a, b in parts]
    if None in lengths:
        raise ValueError("can't work out how long these voices are (tuplets, grace notes or repeats)")
    longest = max(lengths)
    before = notes(text)
    # The item after the >> keeps its duration: LilyPond carries on with
    # the last one written in the passage.
    after = [x for x in items if x[0].pos >= end]
    if after and after[0][1] is None:
        text = _write_duration(text, after[0][0], after[0][2])
    for (a, b), length in sorted(zip(parts, lengths), reverse=True):
        if length == longest:
            continue
        rests = " ".join("r" + d for d in _durations_for(longest - length))
        inner_end = text.rfind("}", a, b)
        if inner_end < 0:  # a voice without braces
            inner_end = b
            while inner_end > a and text[inner_end - 1].isspace():
                inner_end -= 1
            text = text[:inner_end] + " " + rests + text[inner_end:]
        else:
            k = inner_end
            while k > a and text[k - 1].isspace():
                k -= 1
            text = text[:k] + " " + rests + text[k:]
    return _fix_octaves(text, [n.key() for n in before])


# --------------------------------------------------------------------------
# Recording: notes played in time, quantized and written in

def time_at(text, pos):
    """The bar length (whole notes) in effect at `pos`, from the last
    \\time before it (4/4 if none)."""
    found = re.findall(r"\\time\s+(\d+)\s*/\s*(\d+)", text[:pos])
    if not found:
        return Fraction(1)
    n, d = found[-1]
    return Fraction(int(n), int(d))


def record(text, pos, played, start, grid="16", after=True):
    """Writes music played in real time after the item at `pos` (or at `pos`).
    `played`: (onset, release, MIDI key) in quarter notes from where the new
    music begins; `start`: that place's moment in whole notes (for the bar
    lines). Onsets and releases are rounded to the `grid` (e.g. "16"); keys
    starting together form chords, gaps become rests, and notes are split
    and tied at bar lines. Returns (text, start, end of the new music)."""
    unit = Fraction(1, int(grid))

    def tick(quarters):
        return int(round(Fraction(quarters).limit_denominator(10000) / 4 / unit))

    groups = {}
    for onset, release, key in played:
        a = tick(onset)
        b = max(tick(release), a + 1)
        keys, end = groups.get(a, (set(), a))
        keys.add(int(key))
        groups[a] = (keys, max(end, b))
    if not groups:
        raise ValueError("nothing was played")
    onsets = sorted(groups)
    # Players lift their fingers a little early: a gap shorter than an eighth
    # note is the note held, not a rest.
    min_rest = max(1, int(Fraction(1, 8) / unit))
    items = []  # (keys or None for a rest, length in ticks)
    if onsets[0] > 0:
        items.append((None, onsets[0]))
    for i, a in enumerate(onsets):
        keys, end = groups[a]
        nxt = onsets[i + 1] if i + 1 < len(onsets) else None
        stop = end if nxt is None else min(end, nxt)
        if nxt is not None and nxt - stop < min_rest:
            stop = nxt
        items.append((sorted(keys), stop - a))
        if nxt is not None and nxt > stop:
            items.append((None, nxt - stop))

    bar = time_at(text, pos)
    in_bar = Fraction(start).limit_denominator(10000) % bar
    key = key_at(text, pos)
    name = _language_writer(text)
    words, new_pitches = [], []
    for keys, ticks in items:
        length = ticks * unit
        spelled = [spell(k, key) for k in keys] if keys else None
        pieces = []
        while length > 0:
            piece = min(length, bar - in_bar)
            pieces += _durations_for(piece)
            in_bar = (in_bar + piece) % bar
            length -= piece
        if spelled is None:
            words += ["r" + d for d in pieces]
            continue
        names = [name(n, a) for n, a, _ in spelled]
        chord = names[0] if len(names) == 1 else "<" + " ".join(names) + ">"
        for j, d in enumerate(pieces):
            words.append(chord + d + ("~" if j + 1 < len(pieces) else ""))
            new_pitches += [(n, a, o) for n, a, o in spelled]
    music = " ".join(words)

    items_ = _items(text)
    before = notes(text)
    if after:
        at = _item_end(text, items_[_item_index(text, items_, pos)][0])
    else:
        at = pos
    following = [(it, w, eff) for it, w, eff in items_ if it.pos >= at]
    if following and following[0][1] is None:  # the next item keeps its duration
        nxt = following[0][0]
        text = text[:nxt.insert_pos] + following[0][2] + text[nxt.insert_pos:]
    pre = "" if at == 0 or text[at - 1].isspace() else " "
    post = "" if at >= len(text) or text[at].isspace() else " "
    text = text[:at] + pre + music + post + text[at:]
    desired = ([n.key() for n in before if n.pos < at] + new_pitches + [n.key() for n in before if n.pos >= at])
    text = _fix_octaves(text, desired)
    begin = at + len(pre)
    # the new music's end: after as many items as were written
    items_after = _items(text)
    k = next(i for i, (it, _, _) in enumerate(items_after) if _item_start(text, it) >= begin)
    last = items_after[k + len(words) - 1][0]
    return text, begin, _item_end(text, last)


# --------------------------------------------------------------------------
# Lyrics: verses typed as plain text, written as \\addlyrics after a melody

# Blocks that hold settings, not music: lyrics never go after these.
_BLOCKS = ("\\score", "\\layout", "\\header", "\\paper", "\\book", "\\bookpart", "\\with", "\\midi")


def _brackets(text):
    """Matching { } and << >> in the text (not in comments or strings):
    two dicts, opening offset -> closing offset."""
    braces, angles, stack = {}, {}, []
    i = 0
    while i < len(text):
        c = text[i]
        if c == "%":
            if text.startswith("%{", i):
                j = text.find("%}", i + 2)
                i = len(text) if j < 0 else j + 2
            else:
                j = text.find("\n", i)
                i = len(text) if j < 0 else j
            continue
        if c == '"':
            j = i + 1
            while j < len(text) and text[j] != '"':
                j += 2 if text[j] == "\\" else 1
            i = j + 1
            continue
        if text.startswith("<<", i):
            stack.append(("<<", i)); i += 2; continue
        if text.startswith(">>", i):
            while stack and stack[-1][0] != "<<":
                stack.pop()
            if stack:
                angles[stack.pop()[1]] = i
            i += 2
            continue
        if c == "{":
            stack.append(("{", i))
        elif c == "}":
            while stack and stack[-1][0] != "{":
                stack.pop()
            if stack:
                braces[stack.pop()[1]] = i
        i += 1
    return braces, angles


def _word_before(text, pos):
    """The command or word just before pos (skipping spaces): "\\score", "c''"…"""
    j = pos
    while j > 0 and text[j - 1].isspace():
        j -= 1
    k = j
    while k > 0 and not text[k - 1].isspace() and text[k - 1] not in "{}<>":
        k -= 1
    return text[k:j], k


def lyrics_target(text, pos):
    """The melody the lyrics at `pos` belong to: (start, end, voice) where
    start..end is its { } (end after the "}") and voice is its number in a
    << { } \\\\ { } >> passage (0, 1…) or None. In a passage of voices, it is
    that voice's { }; elsewhere, the outermost { } of music around `pos`, not
    reaching into \\score or other blocks, nor out of a << >>."""
    braces, angles = _brackets(text)
    try:
        start, end, parts = _enclosing_voices(text, pos)
        for k, (a, b) in enumerate(parts):
            if a <= pos < b:
                inner = text[a:b]
                lead = len(inner) - len(inner.lstrip())
                if inner.strip().startswith("{"):
                    o = a + lead
                    return o, braces[o] + 1, k
    except ValueError:
        pass
    around = sorted((o for o, c in braces.items() if o < pos < c), reverse=True)  # innermost first
    if not around:
        raise ValueError("not in music")
    target = None
    for o in around:
        if _word_before(text, o)[0] in _BLOCKS:
            break  # \\score { … }: not music itself
        # a << between this { and the one inside it: the music is the inside one
        if target is not None and any(o < a < target for a in angles):
            break
        target = o
    if target is None:
        raise ValueError("not in music")
    return target, braces[target] + 1, None


def _verse_blocks(text, end):
    """The \\addlyrics { } blocks right after a melody ending at `end`, as
    (start of "\\addlyrics", position of "{", position after "}")."""
    braces, _ = _brackets(text)
    out = []
    at = end
    while True:
        m = re.compile(r"\s*\\addlyrics\s*(?:\\with\s*\{[^{}]*\}\s*)?(\{)").match(text, at)
        if not m or m.start(1) not in braces:
            return out
        close = braces[m.start(1)] + 1
        out.append((m.start(), m.start(1), close))
        at = close


def lyrics_to_plain(lily):
    """LilyPond lyrics as plain text: "Twin -- kle star __" -> "Twin-kle star_"."""
    toks = re.findall(r'"(?:\\.|[^"\\])*"|\S+', lily)
    words = []
    join = False
    for t in toks:
        if t == "--":
            join = True
            continue
        if t == "__":
            if words:
                words[-1] += "_"
            continue
        if t.startswith('"') and t.endswith('"') and len(t) >= 2:
            t = t[1:-1].replace('\\"', '"')
        if join and words:
            words[-1] += "-" + t
        else:
            words.append(t)
        join = False
    return " ".join(words)


def _lily_syllable(s):
    if s == "_":
        return "_"  # a note without a syllable
    if re.search(r'[{}"\\#$0-9]', s) or s in ("--", "__"):
        return '"' + s.replace('"', '\\"') + '"'
    return s


def plain_to_lyrics(plain):
    """Plain text as LilyPond lyrics: "Twin-kle star_" -> "Twin -- kle star __"."""
    out = []
    for word in plain.split():
        extend = word.endswith("_") and word != "_"
        if extend:
            word = word.rstrip("_")
        parts = [p for p in re.split(r"(?<=\S)-(?=\S)", word)] if word != "_" else ["_"]
        out.append(" -- ".join(_lily_syllable(p) for p in parts))
        if extend:
            out.append("__")
    return " ".join(out)


_VOICE_DEF = r'\\(?:new|context)\s+Voice\s*=\s*"?([A-Za-z0-9_.-]+)"?\s*(?:\\with\s*\{[^{}]*\}\s*)?'
_MUSIC_PREFIX = r'(?:\\(?:relative|fixed)\s+[a-z]+[\',]*\s*)?'


def _voice_name(text, start):
    """The name of the voice whose music is the { } at `start`: written right
    after \\new Voice = "name" (\\context Voice too), or a variable used
    inside one, as musicxml2ly writes. None if it has no name."""
    before = text[:start]
    m = re.search(_VOICE_DEF + _MUSIC_PREFIX + r'$', before)
    if m:
        return m.group(1)
    m = re.search(r'([A-Za-z]+)\s*=\s*' + _MUSIC_PREFIX + r'$', before)
    if m:
        var = m.group(1)
        use = re.search(_VOICE_DEF + r'\{[^{}]*?\\' + var + r'(?![A-Za-z])', text)
        if use:
            return use.group(1)
    return None


def _lyricsto_blocks(text, voice):
    """The lyrics sung to the voice named `voice` with \\lyricsto, in order:
    (start of \\lyricsto, position of "{", position after "}" of the lyrics,
    end of the \\lyricsto expression), the lyrics written inline or in a
    variable (name = \\lyricmode { … })."""
    braces, _ = _brackets(text)
    out = []
    for m in re.finditer(r'\\lyricsto\s*"?' + re.escape(voice) + r'"?\s*(?:\\lyricmode\s*)?(?:(\{)|\\([A-Za-z]+))', text):
        if m.group(1):
            o = m.start(1)
            use_end = None  # after the lyrics' }
        else:
            use_end = m.end()
            d = re.search(r'(?<![A-Za-z\\])' + m.group(2) + r'\s*=\s*\\lyricmode\s*(\{)', text)
            if not d:
                continue
            o = d.start(1)
        if o in braces:
            out.append((m.start(), o, braces[o] + 1, use_end or braces[o] + 1))
    return out


def _named_voice_end(text, name):
    """Where a \\new Lyrics for the voice `name` can go: right after its
    \\new Voice = "name" { … }, if that stands in a << >>. Else None."""
    braces, angles = _brackets(text)
    for m in re.finditer(_VOICE_DEF + r'(\{)', text):
        if m.group(1) != name or m.start(2) not in braces:
            continue
        close = braces[m.start(2)] + 1
        inside = [o for o, c in angles.items() if o < m.start() and close <= c]
        tighter = [o for o, c in braces.items() if o < m.start() and close <= c]
        if inside and (not tighter or max(inside) > max(tighter)):
            return close
    return None


def _all_verses(text, start, end):
    """The verses of the melody start..end: its \\lyricsto lyrics, then its
    \\addlyrics ones, as (kind, start, "{" position, after "}")."""
    name = _voice_name(text, start)
    found = [("lyricsto",) + b for b in (_lyricsto_blocks(text, name) if name else [])]
    return found + [("addlyrics",) + b + (b[2],) for b in _verse_blocks(text, end)]


def verses(text, pos):
    """The verses of the melody at `pos`, as plain text (see lyrics_to_plain)."""
    start, end, _ = lyrics_target(text, pos)
    return [lyrics_to_plain(text[o + 1:c - 1]) for _, _, o, c, _ in _all_verses(text, start, end)]


_VOICE_SETTINGS = ["\\voiceOne", "\\voiceTwo", "\\voiceThree", "\\voiceFour"]


_STAFF_HEAD = r'\\new\s+Staff(\s*=\s*"([^"]+)")?(\s*\\with\s*\{[^{}]*\})?\s*'


def _staff_for(text, start):
    """The \\new Staff whose music holds the { } at `start` (directly, or
    through a variable it uses): (its name or None, where a name can be
    written in after "Staff", a name to give it). None if not found."""
    braces, _ = _brackets(text)
    outer = min((o for o, c in braces.items() if o <= start < c and _word_before(text, o)[0] not in _BLOCKS),
                default=start)
    before = text[:outer]
    m = re.search(r'([A-Za-z]+)\s*=\s*' + _MUSIC_PREFIX + r'$', before)
    if m:
        use = re.search(_STAFF_HEAD + r'\\' + m.group(1) + r'(?![A-Za-z])', text)
        suggested = m.group(1)
    else:
        use = re.search(_STAFF_HEAD + _MUSIC_PREFIX + r'$', before)
        suggested = "staff"
    if not use:
        return None
    after_staff = use.start() + text[use.start():].index("Staff") + len("Staff")
    return use.group(2), after_staff, suggested


def set_verse(text, pos, index, plain):
    """Sets verse `index` (0 = first) of the melody at `pos` to the plain
    text, adding the verse (index = number of verses) or removing it (empty
    text; \\lyricsto lyrics are only emptied, keeping their Lyrics context).
    A voice of a << { } \\\\ { } >> gets its \\voiceOne… setting written in,
    since \\addlyrics would otherwise lose it. Returns (text, `pos` moved
    along with the text before it)."""
    start, end, voice = lyrics_target(text, pos)
    every = _all_verses(text, start, end)
    words = plain_to_lyrics(plain)
    if index < len(every):
        kind, a, o, c, _ = every[index]
        if kind == "addlyrics" and not words.strip():
            cut_from, cut_to, new = a, c, ""
        else:
            cut_from, cut_to, new = o, c, "{ " + words + " }" if words.strip() else "{ }"
        text = text[:cut_from] + new + text[cut_to:]
        if cut_from < pos:  # lyrics written before the melody (a variable)
            pos += len(new) - (cut_to - cut_from)
            start += len(new) - (cut_to - cut_from) if cut_from < start else 0
        added_lyrics = kind == "addlyrics" and bool(words.strip())
    elif index == len(every):
        if not words.strip():
            return text, pos
        name = _voice_name(text, start)
        lyricsto = [b for b in every if b[0] == "lyricsto"]
        named_at = _named_voice_end(text, name) if name and not lyricsto else None
        if lyricsto or named_at is not None:
            # A named voice: \\addlyrics would hide its name from \\lyricsto; a
            # further Lyrics line is LilyPond's way.
            at = lyricsto[-1][4] if lyricsto else named_at
            line = ' \\new Lyrics \\lyricsto "%s" { %s }' % (name, words)
            text = text[:at] + line + text[at:]
            if at < pos:
                pos += len(line)
            added_lyrics = False
        else:
            addl = [b for b in every if b[0] == "addlyrics"]
            at = addl[-1][3] if addl else end
            with_ = ""
            if voice is not None:
                # Lyrics made inside a << \\\\ >> passage go to the bottom of the
                # score unless told which staff to go under: name it if need be.
                staff = _staff_for(text, start)
                if staff is not None:
                    name, name_at, suggested = staff
                    if name is None:
                        name = suggested
                        insert = ' = "%s"' % name
                        text = text[:name_at] + insert + text[name_at:]
                        if name_at < at:
                            at += len(insert)
                        if name_at < pos:
                            pos += len(insert)
                        if name_at < start:
                            start += len(insert)
                    with_ = ' \\with { alignBelowContext = "%s" }' % name
            text = text[:at] + " \\addlyrics" + with_ + " { " + words + " }" + text[at:]
            added_lyrics = True
    else:
        raise ValueError("no verse %d" % (index + 1))
    if added_lyrics and voice is not None and voice < len(_VOICE_SETTINGS):
        setting = _VOICE_SETTINGS[voice]
        inside = text[start + 1:start + 1 + 40]
        if setting not in inside.split():
            text = text[:start + 1] + " " + setting + text[start + 1:]
            if pos > start:
                pos += len(setting) + 1
    return text, pos


def _syllables(lily):
    """The syllables of LilyPond lyrics (words split at --), without __."""
    toks = re.findall(r'"(?:\\.|[^"\\])*"|\S+', lily)
    return [t for t in toks if t not in ("--", "__")]


def _sung_notes(text, start, end):
    """How many notes of the melody start..end take a syllable of \\addlyrics:
    chords count once, tied-to notes and notes under a slur after its first
    don't (they extend the syllable before), rests and skips don't."""
    items = _items(text)
    count, tied, in_slur = 0, False, False
    for it, _, _ in items:
        if not (start <= it.pos < end):
            continue
        if _is_rest(text, it):
            continue
        m = marks(text, _item_start(text, it))
        if not tied and not in_slur:
            count += 1
        tied = "tie" in m
        if "slur-start" in m:
            in_slur = True
        if "slur-end" in m:
            in_slur = False
    return count


def verse_check(text, pos):
    """For each verse of the melody at `pos`: (syllables, notes to sing)."""
    start, end, _ = lyrics_target(text, pos)
    notes_ = _sung_notes(text, start, end)
    return [(len(_syllables(text[o + 1:c - 1])), notes_) for _, _, o, c, _ in _all_verses(text, start, end)]


def verse_at(text, pos):
    """The verse and word at `pos` in lyrics: (a position in the melody, verse
    index, word index among the verse's plain words), or None."""
    for target_start, target_end in _music_ends(text):
        blocks = _all_verses(text, target_start, target_end)
        for index, (_, _, o, c, _) in enumerate(blocks):
            if o < pos < c:
                # up to the end of the syllable at pos: its word is the last one then
                e = pos
                while e < c - 1 and not text[e].isspace():
                    e += 1
                words = lyrics_to_plain(text[o + 1:e]).split()
                return target_start + 1, index, max(len(words) - 1, 0)
    return None


def _music_ends(text):
    """Every { } of music with lyrics: followed by \\addlyrics, or the music
    of a named voice (\\lyricsto): (start, end after "}")."""
    braces, _ = _brackets(text)
    for o, c in braces.items():
        if re.compile(r"\s*\\addlyrics").match(text, c + 1) or _voice_name(text, o):  # (with or without \\with)
            yield o, c + 1


# --------------------------------------------------------------------------
# New scores from templates

TEMPLATES = {
    "melody": "Melody (one staff)",
    "piano": "Piano",
    "voice-piano": "Voice and piano",
    "satb": "Choir (SATB, four staves)",
    "satb2": "Choir on two staves",
    "lead": "Lead sheet (melody and chords)",
    "quartet": "String quartet",
}


def _string(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def new_score(template="melody", title="", composer="", tonic="c", mode="major",
              time="4/4", tempo=None, bars=4):
    """A new score: the parts of `template` as variables with a shared
    `global` (key, time, tempo), each `bars` bars of rests to fill in."""
    n, d = (int(x) for x in time.split("/"))
    bar_rests = " ".join("r" + x for x in _durations_for(Fraction(n, d)))
    rests = " |\n  ".join([bar_rests] * max(1, int(bars))) + ' \\bar "|."'
    skips = " | ".join(" ".join("s" + x for x in _durations_for(Fraction(n, d))) for _ in range(max(1, int(bars))))
    out = ['\\version "2.26.0"', ""]
    head = []
    if title:
        head.append("  title = " + _string(title))
    if composer:
        head.append("  composer = " + _string(composer))
    if head:
        out += ["\\header {"] + head + ["}", ""]
    out += ["global = {", "  \\key %s \\%s" % (tonic, mode), "  \\time %s" % time]
    if tempo:
        out.append("  \\tempo 4 = %d" % int(tempo))
    out += ["}", ""]

    def part(name, octave, clef=None):
        lines = ["%s = \\relative %s {" % (name, octave), "  \\global"]
        if clef:
            lines.append("  \\clef %s" % clef)
        lines += ["  " + rests, "}", ""]
        return lines

    def staff(var, instrument=None):
        w = ' \\with { instrumentName = %s }' % _string(instrument) if instrument else ""
        return "    \\new Staff%s \\%s" % (w, var)

    if template == "melody":
        out += part("melody", "c''")
        body = [staff("melody")]
    elif template == "piano":
        out += part("right", "c''") + part("left", "c", "bass")
        body = ['    \\new PianoStaff \\with { instrumentName = "Piano" } <<',
                "  " + staff("right"), "  " + staff("left"), "    >>"]
    elif template == "voice-piano":
        out += part("melody", "c''") + part("right", "c''") + part("left", "c", "bass")
        body = [staff("melody", "Voice"),
                '    \\new PianoStaff \\with { instrumentName = "Piano" } <<',
                "  " + staff("right"), "  " + staff("left"), "    >>"]
    elif template == "satb":
        out += (part("soprano", "c''") + part("alto", "c'") +
                part("tenor", "c'", '"treble_8"') + part("bass", "c", "bass"))
        body = ["    \\new ChoirStaff <<", "  " + staff("soprano", "Soprano"), "  " + staff("alto", "Alto"),
                "  " + staff("tenor", "Tenor"), "  " + staff("bass", "Bass"), "    >>"]
    elif template == "satb2":
        out += part("soprano", "c''") + part("alto", "c'") + part("tenor", "c'") + part("bass", "c")
        body = ["    \\new ChoirStaff <<",
                '      \\new Staff \\with { instrumentName = \\markup \\center-column { "Soprano" "Alto" } } <<',
                '        \\new Voice = "soprano" { \\voiceOne \\soprano }',
                '        \\new Voice = "alto" { \\voiceTwo \\alto }',
                "      >>",
                '      \\new Staff \\with { instrumentName = \\markup \\center-column { "Tenor" "Bass" } } <<',
                "        \\clef bass",
                '        \\new Voice = "tenor" { \\voiceOne \\tenor }',
                '        \\new Voice = "bass" { \\voiceTwo \\bass }',
                "      >>",
                "    >>"]
    elif template == "lead":
        out += part("melody", "c''")
        out += ["harmonies = \\chordmode {", "  " + skips, "}", ""]
        body = ["    \\new ChordNames \\harmonies", staff("melody")]
    elif template == "quartet":
        out += (part("violinI", "c''") + part("violinII", "c''") +
                part("viola", "c'", "alto") + part("cello", "c", "bass"))
        body = ["    \\new StaffGroup <<", "  " + staff("violinI", "Violin I"), "  " + staff("violinII", "Violin II"),
                "  " + staff("viola", "Viola"), "  " + staff("cello", "Cello"), "    >>"]
    else:
        raise ValueError("unknown template " + template)
    out += ["\\score {", "  <<"] + body + ["  >>", "  \\layout { }", "  \\midi { }", "}", ""]
    return "\n".join(out)


# --------------------------------------------------------------------------
# Score settings: header fields, paper size, staff size

HEADER_FIELDS = ["title", "subtitle", "composer", "arranger", "poet", "copyright"]
_PAPER = re.compile(r'#\(set-default-paper-size\s+"([a-z0-9]+?)(landscape)?"\s*\)')
_STAFF_SIZE = re.compile(r"#\(set-global-staff-size\s+([0-9.]+)\s*\)")


def _top_header(text):
    """The \\header { } that is not inside a \\score or \\book: (start of
    "\\header", "{", after "}"), or None."""
    braces, _ = _brackets(text)
    for m in re.finditer(r"\\header\s*(\{)", text):
        o = m.start(1)
        if o in braces and not any(a < m.start() and braces[a] > o for a in braces if a != o):
            return m.start(), o, braces[o] + 1
    return None


def _header_value(text, start, end, field):
    m = re.compile(r'(?<![A-Za-z-])' + field + r'\s*=\s*("(?:\\.|[^"\\])*")').search(text, start, end)
    if not m:
        return None, None
    v = m.group(1)[1:-1].replace('\\"', '"').replace("\\\\", "\\")
    return v, m


def settings(text):
    """The score's settings: header fields (plain strings), paper size,
    landscape, staff size (None if not set)."""
    out = {}
    h = _top_header(text)
    for f in HEADER_FIELDS:
        out[f] = _header_value(text, h[1], h[2], f)[0] if h else None
    m = _PAPER.search(text)
    out["paper"] = m.group(1) if m else None
    out["landscape"] = bool(m and m.group(2))
    m = _STAFF_SIZE.search(text)
    out["staff_size"] = float(m.group(1)) if m else None
    return out


def set_settings(text, values):
    """Sets settings given in `values` (keys as in settings(); an empty
    string or None removes a header field or setting)."""
    # Header fields
    for f in HEADER_FIELDS:
        if f not in values:
            continue
        v = values[f] or ""
        h = _top_header(text)
        if h is None:
            if not v:
                continue
            at = _after_version(text)
            text = text[:at] + "\\header {\n}\n" + text[at:]
            h = _top_header(text)
        _, m = _header_value(text, h[1], h[2], f)
        line = f + " = " + _string(v)
        if m:
            if v:
                text = text[:m.start()] + line + text[m.end():]
            else:  # the whole line goes
                a = text.rfind("\n", 0, m.start()) + 1
                b = text.find("\n", m.end())
                b = len(text) if b < 0 else b + 1
                if not text[a:m.start()].strip():
                    text = text[:a] + text[b:]
                else:  # on a line with other things: just the field, and its space
                    e = m.end()
                    while e < len(text) and text[e] == " ":
                        e += 1
                    text = text[:m.start()] + text[e:]
        elif v:
            close = h[2] - 1
            if "\n" in text[h[1]:close]:  # a header over several lines: a line of its own
                text = text[:close] + "  " + line + "\n" + text[close:]
            else:  # \\header { title = "…" }: on the same line
                head = text[:close].rstrip()
                text = head + " " + line + " " + text[close:]
    # Paper size and orientation
    if "paper" in values or "landscape" in values:
        cur = settings(text)
        paper = values.get("paper", cur["paper"])
        landscape = values.get("landscape", cur["landscape"])
        line = '#(set-default-paper-size "%s%s")' % (paper, "landscape" if landscape else "") if paper else ""
        text = _set_top_line(text, _PAPER, line)
    # Staff size
    if "staff_size" in values:
        v = values["staff_size"]
        line = "#(set-global-staff-size %g)" % float(v) if v else ""
        text = _set_top_line(text, _STAFF_SIZE, line)
    return text


def _set_top_line(text, pattern, line):
    """Replaces the setting `pattern` finds with `line`, adds it after the
    \\version line, or removes it (with its line break) if `line` is empty."""
    m = pattern.search(text)
    if m and line:
        return text[:m.start()] + line + text[m.end():]
    if m:
        end = m.end() + 1 if text[m.end():m.end() + 1] == "\n" else m.end()
        return text[:m.start()] + text[end:]
    if line:
        at = _after_version(text)
        return text[:at] + line + "\n" + text[at:]
    return text


def _after_version(text):
    """Where top-level settings go: after the \\version line, or at the start."""
    m = re.search(r'\\version\s*"[^"]*"[^\n]*\n?', text)
    return m.end() if m else 0


# --------------------------------------------------------------------------
# Copy and paste of music

def copy(text, positions):
    """The music of the items at `positions` (one stretch of one voice), to
    paste elsewhere: (fragment as written, with its first duration written
    out, and the absolute pitches of its notes)."""
    items = _items(text)
    ks = sorted({_item_index(text, items, p) for p in positions})
    a, b = ks[0], ks[-1]
    start = _item_start(text, items[a][0])
    end = _item_end(text, items[b][0])
    if not _same_sequence(text, start, end):
        raise ValueError("copy notes from one voice")
    fragment = text[start:end]
    first, written, effective = items[a]
    if written is None:
        k = first.insert_pos - start
        fragment = fragment[:k] + effective + fragment[k:]
    pitches = [n.key() for n in notes(text) if start <= n.pos < end]
    return fragment, pitches


def paste(text, pos, fragment, pitches, after=True):
    """Inserts copied music after the item at `pos` (or at `pos`), each note
    sounding at its copied pitch whatever the octave marks around it
    (\\relative or not). Returns (text, start, end of the pasted music)."""
    items_ = _items(text)
    before = notes(text)
    at = _item_end(text, items_[_item_index(text, items_, pos)][0]) if after else pos
    following = [(it, w, eff) for it, w, eff in items_ if it.pos >= at]
    if following and following[0][1] is None:  # the next item keeps its duration
        nxt = following[0][0]
        text = text[:nxt.insert_pos] + following[0][2] + text[nxt.insert_pos:]
    pre = "" if at == 0 or text[at - 1].isspace() else " "
    post = "" if at >= len(text) or text[at].isspace() else " "
    text = text[:at] + pre + fragment + post + text[at:]
    desired = ([n.key() for n in before if n.pos < at] + [tuple(p) for p in pitches]
               + [n.key() for n in before if n.pos >= at])
    text = _fix_octaves(text, desired)
    begin = at + len(pre)
    # the end: as far as the fragment's length moved by octave fixes is hard to
    # know, so count its items instead
    count = len(_items("{ " + fragment + " }"))
    after_items = _items(text)
    k = next(i for i, (it, _, _) in enumerate(after_items) if _item_start(text, it) >= begin)
    return text, begin, _item_end(text, after_items[k + count - 1][0])


# --------------------------------------------------------------------------
# Changes in the music: clef, key, time, tempo, bar lines, text, repeats

# Each kind of change, and how to find one already written in the same place.
_CHANGES = {
    "clef": re.compile(r'\\clef\s+(?:"[^"]*"|[A-Za-z_0-9^]+)'),
    "key": re.compile(r"\\key\s+[a-z]+\s+\\[a-z]+"),
    "time": re.compile(r"\\time\s+\d+\s*/\s*\d+"),
    "tempo": re.compile(r'\\tempo\s+(?:"[^"]*"\s*)?(?:\d+\.*\s*=\s*\d+(?:\s*-\s*\d+)?)?'),
    "bar": re.compile(r'\\bar\s+"[^"]*"'),
}


def change(text, pos, kind, written, after=False):
    """Writes a change (`written`, e.g. '\\key d \\major') before the item at
    `pos`, or after it with `after` (for bar lines at the end of a passage).
    One of the same kind already there (between that item and its neighbour)
    is replaced. Returns the text."""
    items = _items(text)
    k = _item_index(text, items, pos)
    it = items[k][0]
    if after:
        at = _item_end(text, it)
        nxt = items[k + 1][0] if k + 1 < len(items) else None
        gap = (at, _item_start(text, nxt) if nxt is not None else at)
    else:
        at = _item_start(text, it)
        prev = items[k - 1][0] if k > 0 else None
        gap = (_item_end(text, prev) if prev is not None else at, at)
    before = notes(text)
    m = _CHANGES[kind].search(text, gap[0], gap[1]) if gap[1] > gap[0] else None
    if m and _same_sequence(text, gap[0], gap[1]):
        old = (m.start(), m.end())
        new_at = m.start()
        text = text[:m.start()] + written + text[m.end():]
    else:
        old = (at, at)
        new_at = at + 1 if after else at
        text = text[:at] + (" " + written if after else written + " ") + text[at:]
    # A \\key has a pitch of its own: the changes' pitches stay as written,
    # every other note sounds as before.
    kept = iter(n.key() for n in before if not (old[0] <= n.pos < old[1]))
    desired = [n.key() if new_at <= n.pos < new_at + len(written) else next(kept) for n in notes(text)]
    return _fix_octaves(text, desired)


def add_text(text, pos, words, above=True, bold=False, italic=False):
    """Text over (or under) the note, chord or rest at `pos`: ^"dolce", or
    ^\\markup { \\bold \\italic "dolce" } with a style."""
    items = _items(text)
    it = items[_item_index(text, items, pos)][0]
    end = _item_end(text, it)
    styles = ("\\bold " if bold else "") + ("\\italic " if italic else "")
    written = "\\markup { %s%s }" % (styles, _string(words)) if styles else _string(words)
    return text[:end] + ("^" if above else "_") + written + text[end:]


def wrap(text, positions, before_text, after_text=""):
    """Puts `before_text` … `after_text` around the items at `positions` (one
    stretch of one voice): "\\tuplet 3/2 {" … "}", "\\grace {" … "}",
    "\\ottava #1" … "\\ottava #0". The item after keeps its duration (grace
    notes would otherwise pass theirs on), and every note sounds as before."""
    items = _items(text)
    ks = sorted({_item_index(text, items, p) for p in positions})
    start = _item_start(text, items[ks[0]][0])
    end = _item_end(text, items[ks[-1]][0])
    if not _same_sequence(text, start, end):
        raise ValueError("select notes in one voice")
    before = notes(text)
    nxt = items[ks[-1] + 1] if ks[-1] + 1 < len(items) else None
    if nxt is not None and nxt[1] is None:
        text = text[:nxt[0].insert_pos] + nxt[2] + text[nxt[0].insert_pos:]
    first = items[ks[0]]
    inner = text[start:end]
    if first[1] is None:  # inside braces, the first item says its own duration
        k = first[0].insert_pos - start
        inner = inner[:k] + first[2] + inner[k:]
    text = text[:start] + before_text + " " + inner + (" " + after_text if after_text else "") + text[end:]
    return _fix_octaves(text, [n.key() for n in before])


def first_ending(text, positions):
    """Makes the items at `positions`, the end of a \\repeat volta's music,
    its first ending: \\repeat volta 2 { A B } -> \\repeat volta 2 { A }
    \\alternative { { B } }."""
    braces, _ = _brackets(text)
    items = _items(text)
    ks = sorted({_item_index(text, items, p) for p in positions})
    start = _item_start(text, items[ks[0]][0])
    end = _item_end(text, items[ks[-1]][0])
    rep = None
    for m in re.finditer(r"\\repeat\s+volta\s+\d+\s*(\{)", text):
        o = m.start(1)
        if o in braces and o < start and end <= braces[o]:
            rep = (o, braces[o])
    if rep is None:
        raise ValueError("select the end of a repeat (\\repeat volta)")
    o, c = rep
    if text[end:c].strip():
        raise ValueError("the first ending is the last notes of the repeat: select up to its end")
    if re.compile(r"\s*\\alternative").match(text, c + 1):
        raise ValueError("this repeat has endings already: use Next ending")
    before = notes(text)
    first, written, effective = items[ks[0]]
    inner = text[start:end]
    if written is None:
        k = first.insert_pos - start
        inner = inner[:k] + effective + inner[k:]
    body_end = start  # (a bar check before the ending stays out of the body)
    while body_end > o + 1 and (text[body_end - 1].isspace() or text[body_end - 1] == "|"):
        body_end -= 1
    text = text[:body_end] + " }" + " \\alternative { { " + inner + " } }" + text[c + 1:]
    return _fix_octaves(text, [n.key() for n in before])


def next_ending(text, positions):
    """Makes the items at `positions`, right after a repeat's \\alternative,
    its next ending."""
    braces, _ = _brackets(text)
    items = _items(text)
    ks = sorted({_item_index(text, items, p) for p in positions})
    start = _item_start(text, items[ks[0]][0])
    end = _item_end(text, items[ks[-1]][0])
    alt = None
    for m in re.finditer(r"\\alternative\s*(\{)", text):
        o = m.start(1)
        if o in braces and braces[o] < start and not text[braces[o] + 1:start].strip().strip("|").strip():
            alt = (o, braces[o])
    if alt is None:
        raise ValueError("select the notes right after the repeat's endings")
    if not _same_sequence(text, start, end):
        raise ValueError("select notes in one voice")
    o, c = alt
    before = notes(text)
    first, written, effective = items[ks[0]]
    inner = text[start:end]
    if written is None:
        k = first.insert_pos - start
        inner = inner[:k] + effective + inner[k:]
    nxt = items[ks[-1] + 1] if ks[-1] + 1 < len(items) else None
    if nxt is not None and nxt[1] is None:  # the music after keeps its duration
        text = text[:nxt[0].insert_pos] + nxt[2] + text[nxt[0].insert_pos:]
    rest = text[c + 1:start] + text[end:]
    rest = re.sub(r"^(\s*\|)\s*\|", r"\1", rest)  # the bar check left behind, now doubled
    text = text[:c] + "{ " + inner + " } " + text[c:c + 1] + rest
    return _fix_octaves(text, [n.key() for n in before])


def repeat(text, positions, times=2):
    """Puts the items at `positions` (one stretch of one voice) in
    \\repeat volta `times` { … }."""
    items = _items(text)
    ks = sorted({_item_index(text, items, p) for p in positions})
    start = _item_start(text, items[ks[0]][0])
    end = _item_end(text, items[ks[-1]][0])
    if not _same_sequence(text, start, end):
        raise ValueError("select notes in one voice")
    before = notes(text)
    text = text[:start] + "\\repeat volta %d { " % int(times) + text[start:end] + " }" + text[end:]
    return _fix_octaves(text, [n.key() for n in before])


# --------------------------------------------------------------------------
# Entry point for Kotlin: one JSON request in, one JSON reply out.


def call(request_json):
    req = json.loads(request_json)
    op = req["op"]
    if op == "templates":
        return json.dumps({"templates": [{"id": k, "name": v} for k, v in TEMPLATES.items()]})
    if op == "new_score":
        try:
            return json.dumps({"text": new_score(**{k: v for k, v in req.items() if k != "op"})})
        except Exception as e:
            return json.dumps({"error": "%s: %s" % (type(e).__name__, e)})
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
        elif op == "insert":
            if "steps" in req:
                req["keys"] = keys_for_steps(text, req["pos"], req["steps"])
            filled = None
            if req.get("fill") and req.get("after") and req.get("duration"):
                filled = fill(text, req["pos"], req.get("keys", []), req["duration"])
            if filled:
                out, start = filled
            else:
                out, start = insert(text, req["pos"], req.get("keys", []), req.get("duration"),
                                    req.get("after", False))
            return json.dumps({"text": out, "start": start, "keys": req.get("keys", [])})
        elif op == "toggle":
            out = toggle(text, req["pos"], req["what"])
        elif op == "multi":
            out, positions = multi(text, req["positions"], req["edit"], req.get("arg"))
            return json.dumps({"text": out, "positions": positions})
        elif op == "span":
            out, positions = span(text, req["pairs"], req["kind"], req.get("positions", []))
            return json.dumps({"text": out, "positions": positions})
        elif op == "add_voice":
            out, start = add_voice(text, req["positions"])
            return json.dumps({"text": out, "start": start})
        elif op == "remove_voice":
            out = remove_voice(text, req["pos"])
        elif op == "fill":
            r = fill(text, req["pos"], req.get("keys", []), req["duration"])
            if r is None:
                return json.dumps({"none": True})
            return json.dumps({"text": r[0], "start": r[1]})
        elif op == "add_to_chord":
            if "steps" in req:
                req["keys"] = keys_for_steps(text, req["pos"], req["steps"])
            out, start = add_to_chord(text, req["pos"], req.get("keys", []))
            return json.dumps({"text": out, "start": start, "keys": req.get("keys", [])})
        elif op == "respell":
            out = respell(text, req["pos"])
        elif op == "voice_lengths":
            return json.dumps({"passages": [
                {"start": a, "end": b, "lengths": [str(x) for x in lengths]}
                for a, b, lengths in voice_lengths(text)]})
        elif op == "pad_voices":
            out = pad_voices(text, req["pos"])
        elif op == "record":
            out, start, end = record(text, req["pos"], req["played"], req["start"], req.get("grid", "16"),
                                     req.get("after", True))
            return json.dumps({"text": out, "start": start, "end": end})
        elif op == "verses":
            return json.dumps({"verses": verses(text, req["pos"])})
        elif op == "set_verse":
            out, pos = set_verse(text, req["pos"], req["index"], req["plain"])
            return json.dumps({"text": out, "pos": pos})
        elif op == "verse_check":
            return json.dumps({"verses": [{"syllables": a, "notes": b} for a, b in verse_check(text, req["pos"])]})
        elif op == "verse_at":
            found = verse_at(text, req["pos"])
            return json.dumps({"found": None if found is None else {"melody": found[0], "verse": found[1], "word": found[2]}})
        elif op == "settings":
            return json.dumps({"settings": settings(text)})
        elif op == "set_settings":
            out = set_settings(text, req["values"])
        elif op == "copy":
            fragment, pitches = copy(text, req["positions"])
            return json.dumps({"fragment": fragment, "pitches": [[n, float(a), o] for n, a, o in pitches]})
        elif op == "paste":
            out, start, end = paste(text, req["pos"], req["fragment"],
                                    [(n, Fraction(a).limit_denominator(4), o) for n, a, o in req["pitches"]],
                                    req.get("after", True))
            return json.dumps({"text": out, "start": start, "end": end})
        elif op == "change":
            out = change(text, req["pos"], req["kind"], req["written"], req.get("after", False))
        elif op == "add_text":
            out = add_text(text, req["pos"], req["words"], req.get("above", True), req.get("bold", False), req.get("italic", False))
        elif op == "wrap":
            out = wrap(text, req["positions"], req["before"], req.get("after", ""))
        elif op == "first_ending":
            out = first_ending(text, req["positions"])
        elif op == "next_ending":
            out = next_ending(text, req["positions"])
        elif op == "repeat":
            out = repeat(text, req["positions"], req.get("times", 2))
        elif op == "marks":
            return json.dumps({"marks": marks(text, req["pos"])})
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
