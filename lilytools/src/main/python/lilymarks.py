"""Marks in LilyPond text: dynamics, articulations, text, tempo, rehearsal
marks, hairpins, slurs, ties, fingerings, pedals, spanners — what is attached
to notes or stands between them, as opposed to the notes themselves.

So a tap on "mf" or "rit." in the score selects that mark (not its note),
and Delete, direction and text changes act on the mark alone. The same file
is lilydesk's lilydesk/edit/marks.py; keep them in step.
"""
from __future__ import annotations

import re
from dataclasses import dataclass

DYNAMICS = {"ppppp", "pppp", "ppp", "pp", "p", "mp", "mf", "f", "ff", "fff", "ffff",
            "fffff", "fp", "sf", "sff", "sp", "spp", "sfz", "rfz", "fz", "n", "sfp"}
HAIRPINS = {"<", ">", "!", "cresc", "decresc", "dim", "endcresc", "enddecresc", "enddim"}
ARTICULATIONS = {
    "accent", "espressivo", "marcato", "portato", "staccatissimo", "staccato", "tenuto",
    "prall", "mordent", "prallprall", "prallmordent", "upprall", "downprall", "upmordent",
    "downmordent", "pralldown", "prallup", "lineprall", "turn", "reverseturn", "trill",
    "shortfermata", "fermata", "longfermata", "verylongfermata", "henzeshortfermata",
    "henzelongfermata", "segno", "coda", "varcoda", "upbow", "downbow", "open", "stopped",
    "flageolet", "thumb", "halfopen", "snappizzicato", "lheel", "rheel", "ltoe", "rtoe",
    "signumcongruentiae", "accentus", "circulus", "ictus", "semicirculus", "laissezVibrer",
    "repeatTie", "arpeggio", "glissando",
}
SPANNERS = {"startTrillSpan", "stopTrillSpan", "startTextSpan", "stopTextSpan", "sustainOn",
            "sustainOff", "sostenutoOn", "sostenutoOff", "unaCorda", "treCorde", "breathe",
            "startGroup", "stopGroup", "\\(", "\\)"}
# The shorthand articulations after a direction: c-. c-> c-^ c-_ c-! c-+ c--
SHORTHAND = {".": "staccato", ">": "accent", "^": "marcato", "_": "tenuto",
             "!": "staccatissimo", "+": "stopped", "-": "tenuto"}

_WORD = re.compile(r"\\([A-Za-z]+|[<>!()])")
_STRING = re.compile(r'"(?:[^"\\]|\\.)*"')
_TEMPO_REST = re.compile(r'\s*(\d+\.*\s*=\s*\d+(?:\s*[-~]\s*\d+)?)')


@dataclass(frozen=True)
class Mark:
    start: int
    end: int            # exclusive
    kind: str           # dynamic | hairpin | articulation | text | tempo | rehearsal |
                        # fingering | spanner | slur | phrasing | tie | ottava
    value: str          # e.g. "mf", "staccato", the text of a text mark
    direction: str      # "^", "_", "-" or "" (none written)

    @property
    def can_flip(self) -> bool:
        return self.kind in ("dynamic", "articulation", "text", "fingering", "slur", "tie")


def _skip_ws(text: str, i: int) -> int:
    while i < len(text) and text[i] in " \t\r\n":
        i += 1
    return i


def _balanced(text: str, i: int) -> int:
    """text[i] is '{' or '(': the index after its partner (strings respected)."""
    open_, close = text[i], "}" if text[i] == "{" else ")"
    depth = 0
    while i < len(text):
        c = text[i]
        if c == '"':
            m = _STRING.match(text, i)
            i = m.end() if m else len(text)
            continue
        if c == "%" and open_ == "{":
            nl = text.find("\n", i)
            i = len(text) if nl < 0 else nl
            continue
        if c == open_:
            depth += 1
        elif c == close:
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return len(text)


def _scheme(text: str, i: int) -> int:
    """text[i] == '#': the end of the Scheme datum."""
    i += 1
    if i < len(text) and text[i] in "'`":
        i += 1
    if i < len(text) and text[i] == "(":
        return _balanced(text, i)
    if i < len(text) and text[i] == '"':
        m = _STRING.match(text, i)
        return m.end() if m else len(text)
    while i < len(text) and not text[i].isspace() and text[i] not in "{}()":
        i += 1
    return i


def _markup_arg(text: str, i: int) -> int:
    """The end of one markup argument starting at (or after spaces from) i:
    {…}, "…", a word, or \\command args… ending in one of those."""
    for _ in range(32):
        i = _skip_ws(text, i)
        if i >= len(text):
            return i
        c = text[i]
        if c == "{":
            return _balanced(text, i)
        if c == '"':
            m = _STRING.match(text, i)
            return m.end() if m else len(text)
        if c == "#":
            i = _scheme(text, i)
            continue
        if c == "\\":
            m = _WORD.match(text, i)
            if not m:
                return i + 1
            i = m.end()
            continue        # a markup command: its argument follows
        j = i
        while j < len(text) and not text[j].isspace() and text[j] not in '{}"\\':
            j += 1
        return j
    return i


def _text_value(src: str) -> str:
    """The words of a markup or string, for editing ("rit.", "dolce")."""
    strings = _STRING.findall(src)
    if strings:
        return " ".join(s[1:-1].replace('\\"', '"') for s in strings)
    body = re.sub(r"\\[A-Za-z-]+|#\S+|[{}]", " ", src)
    return " ".join(body.split())


def mark_from(text: str, i: int) -> Mark | None:
    """The mark that starts at offset i, if one does."""
    if i < 0 or i >= len(text):
        return None
    start = i
    direction = ""
    if text[i] in "-^_" and i + 1 < len(text):
        nxt = text[i + 1]
        if nxt == text[i] and (i == 0 or text[i - 1].isspace()):
            return None      # lyrics: a hyphen " -- " or an extender " __ "
        if nxt in SHORTHAND and text[i] in "-^_":
            # c-. c-> …  (but not c-- followed by a digit? that's tenuto anyway)
            if not (nxt == "-" and text[i] == "-" and i + 2 < len(text) and text[i + 2] == "-"):
                return Mark(start, i + 2, "articulation", SHORTHAND[nxt], text[i])
        direction = text[i]
        i += 1
        if i < len(text) and text[i].isdigit():
            j = i
            while j < len(text) and text[j].isdigit():
                j += 1
            return Mark(start, j, "fingering", text[i:j], direction)
        if i < len(text) and text[i] == '"':
            m = _STRING.match(text, i)
            if m:
                return Mark(start, m.end(), "text", _text_value(m.group(0)), direction)
            return None
        if i < len(text) and text[i] in "()~[]":
            pass     # c^( : handled below with the direction
    c = text[i] if i < len(text) else ""
    if c == "(":
        return Mark(start, i + 1, "slur", "slur-start", direction)
    if c == ")":
        return Mark(start, i + 1, "slur", "slur-end", direction)
    if c == "~":
        return Mark(start, i + 1, "tie", "tie", direction)
    if c != "\\":
        return None
    m = _WORD.match(text, i)
    if not m:
        return None
    word = m.group(1)
    end = m.end()
    if word == "markup":
        if not direction and text[start] == "\\":
            return None          # \markup on its own (a header, a title): not a mark
        end = _markup_arg(text, end)
        return Mark(start, end, "text", _text_value(text[m.end():end]), direction)
    if word in DYNAMICS:
        return Mark(start, end, "dynamic", word, direction)
    if word in HAIRPINS:
        return Mark(start, end, "hairpin", word, direction)
    if word in ARTICULATIONS:
        return Mark(start, end, "articulation", word, direction)
    if word in ("(", ")"):
        return Mark(start, end, "phrasing", "phrasing-" + ("start" if word == "(" else "end"), direction)
    if word in SPANNERS:
        return Mark(start, end, "spanner", word, direction)
    if direction:
        return None
    if word == "tempo":
        j = _skip_ws(text, end)
        value = ""
        if j < len(text) and (text[j] == '"' or text.startswith("\\markup", j)):
            k = _markup_arg(text, j + len("\\markup") if text.startswith("\\markup", j) else j)
            value = _text_value(text[j:k])
            end = k
        r = _TEMPO_REST.match(text, end)
        if r:
            end = r.end()
            value = (value + " " + r.group(1)).strip()
        return Mark(start, end, "tempo", value, "")
    if word == "mark":
        j = _skip_ws(text, end)
        if text.startswith("\\default", j):
            return Mark(start, j + len("\\default"), "rehearsal", "", "")
        if j < len(text) and text[j] == "#":
            return Mark(start, _scheme(text, j), "rehearsal", text[j:_scheme(text, j)], "")
        if j < len(text) and (text[j] == '"' or text.startswith("\\markup", j)):
            k = _markup_arg(text, j + len("\\markup") if text.startswith("\\markup", j) else j)
            return Mark(start, k, "rehearsal", _text_value(text[j:k]), "")
        return Mark(start, end, "rehearsal", "", "")
    if word == "ottava":
        j = _skip_ws(text, end)
        if j < len(text) and text[j] == "#":
            k = _scheme(text, j)
            return Mark(start, k, "ottava", text[j + 1:k], "")
        return None
    return None


def mark_at(text: str, off: int, reach: int = 400) -> Mark | None:
    """The mark the offset is inside (the innermost-starting one)."""
    lo = max(0, off - reach)
    for s in range(min(off, len(text) - 1), lo - 1, -1):
        if text[s] in "-^_\\(~)":
            m = mark_from(text, s)
            if m and m.start <= off < m.end:
                # A direction before it belongs to the same mark (c^\mf).
                if s > 0 and text[s] == "\\" and text[s - 1] in "-^_":
                    outer = mark_from(text, s - 1)
                    if outer and outer.end == m.end:
                        return outer
                return m
    return None


def remove(text: str, mark: Mark) -> tuple[str, int]:
    """The text without the mark; and where the cursor goes."""
    start, end = mark.start, mark.end
    if mark.kind in ("tempo", "rehearsal", "ottava"):
        # Commands of their own between notes: take one space with them.
        while end < len(text) and text[end] in " \t":
            end += 1
        if end < len(text) and text[end] == "\n" or end == len(text):
            while start > 0 and text[start - 1] in " \t":
                start -= 1
    return text[:start] + text[end:], start


def set_direction(text: str, mark: Mark, where: str) -> str:
    """where: "up" (^), "down" (_) or "auto" (-, or nothing where allowed)."""
    d = {"up": "^", "down": "_", "auto": "-"}[where]
    if not mark.can_flip:
        raise ValueError(f"a {mark.kind} has no direction to change")
    body_start = mark.start + len(mark.direction)
    if mark.kind == "articulation" and mark.direction and text[body_start] in SHORTHAND \
            and not text[body_start:].startswith("\\"):
        return text[:mark.start] + d + text[body_start:]
    if d == "-" and (mark.kind in ("dynamic", "articulation", "slur", "tie")):
        d = ""      # c\mf, c\staccato, c( need no "-"
    return text[:mark.start] + d + text[body_start:]


def replace_value(text: str, mark: Mark, value: str) -> str:
    """A dynamic for another (mf -> f), or new words for a text mark."""
    src = text[mark.start:mark.end]
    body = mark.start + len(mark.direction)
    if mark.kind == "dynamic":
        if value not in DYNAMICS:
            raise ValueError(f"not a dynamic: {value}")
        return text[:body] + "\\" + value + text[mark.end:]
    if mark.kind == "text":
        quoted = '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'
        strings = list(_STRING.finditer(src))
        if len(strings) == 1:
            s = strings[0]
            return text[:mark.start + s.start()] + quoted + text[mark.start + s.end():]
        return text[:body] + quoted + text[mark.end:]
    raise ValueError(f"a {mark.kind} has no text to change")
