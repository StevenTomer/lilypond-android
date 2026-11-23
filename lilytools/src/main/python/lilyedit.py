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
