"""Imports from other formats, using LilyPond's own Python tools (the ones
`lilypond` ships as midi2ly, convert-ly and musicxml2ly), run in-process.

The tools are scripts meant to be run once per process, keeping their
options in module globals, so each call reloads the module and sets
sys.argv. Their console output is captured and returned as the log.
"""

import importlib
import io
import json
import os
import sys
import tempfile


def _fresh(name):
    """Imports a module, or runs it again if it was imported before."""
    if name in sys.modules:
        return importlib.reload(sys.modules[name])
    return importlib.import_module(name)


def _run(module_name, argv):
    """Runs a LilyPond script's main() with argv; returns (ok, log)."""
    # lilylib, when imported, re-wraps sys.stdin/stdout/stderr around their
    # detached byte streams (Chaquopy's console streams can't be detached),
    # so give it in-memory ones; their bytes are the log.
    saved = (sys.argv, sys.stdin, sys.stdout, sys.stderr)
    out, err = io.BytesIO(), io.BytesIO()
    sys.argv = argv
    sys.stdin = io.TextIOWrapper(io.BytesIO(), encoding="utf-8")
    sys.stdout = io.TextIOWrapper(out, encoding="utf-8", write_through=True)
    sys.stderr = io.TextIOWrapper(err, encoding="utf-8", write_through=True)
    ok = True
    try:
        # lilylib also takes the program name from sys.argv when imported
        _fresh("lilylib")
        module = _fresh(module_name)  # fresh option globals
        try:
            module.main()
        except SystemExit as e:
            ok = e.code in (None, 0)
    finally:
        for stream in (sys.stdout, sys.stderr):
            try:
                stream.flush()
            except Exception:
                pass
        sys.argv, sys.stdin, sys.stdout, sys.stderr = saved
    log = (out.getvalue() + err.getvalue()).decode("utf-8", "replace")
    return ok, log.strip()


# General MIDI programs, by family, for naming tracks in the import dialog.
_GM_FAMILIES = ["piano", "chromatic percussion", "organ", "guitar", "bass", "strings",
                "ensemble", "brass", "reed", "pipe", "synth lead", "synth pad",
                "synth effects", "ethnic", "percussive", "sound effects"]


def _chunks(data):
    """A Standard MIDI File's header fields and its chunks as (type, bytes)."""
    if data[:4] != b"MThd":
        raise ValueError("not a MIDI file")
    length = int.from_bytes(data[4:8], "big")
    fmt, ntracks, division = (int.from_bytes(data[8 + 2 * i:10 + 2 * i], "big") for i in range(3))
    pos, chunks = 8 + length, []
    while pos + 8 <= len(data):
        kind = data[pos:pos + 4]
        n = int.from_bytes(data[pos + 4:pos + 8], "big")
        chunks.append((kind, data[pos + 8:pos + 8 + n]))
        pos += 8 + n
    return fmt, division, chunks


def _track_info(track):
    """Name, number of notes, channels and first program of one track."""
    name, notes, channels, program = None, 0, set(), None
    i, running = 0, 0
    while i < len(track):
        while track[i] & 0x80:  # delta time
            i += 1
        i += 1
        if i >= len(track):
            break
        status = track[i]
        if status == 0xFF:
            kind = track[i + 1]
            i += 2
            n = 0
            while track[i] & 0x80:
                n = (n << 7) | (track[i] & 0x7F); i += 1
            n = (n << 7) | track[i]; i += 1
            if kind in (0x03, 0x04) and name is None:
                name = track[i:i + n].decode("latin-1").strip() or None
            i += n
            continue
        if status in (0xF0, 0xF7):
            i += 1
            n = 0
            while track[i] & 0x80:
                n = (n << 7) | (track[i] & 0x7F); i += 1
            n = (n << 7) | track[i]; i += 1
            i += n
            continue
        if status & 0x80:
            running = status
            i += 1
        kind, channel = running & 0xF0, running & 0x0F
        size = 1 if kind in (0xC0, 0xD0) else 2
        data = track[i:i + size]
        i += size
        if kind == 0x90 and len(data) == 2 and data[1] > 0:
            notes += 1
            channels.add(channel)
        elif kind == 0xC0 and program is None and data:
            program = data[0]
    return name, notes, channels, program


def midi_tracks(midi_path):
    """The tracks of a MIDI file, for choosing which to import: a list of
    {index, name, notes, drums, instrument}. Tracks without notes (tempo,
    time signatures) are always kept and not listed."""
    with open(midi_path, "rb") as f:
        _, _, chunks = _chunks(f.read())
    out = []
    for index, track in enumerate(c for kind, c in chunks if kind == b"MTrk"):
        name, notes, channels, program = _track_info(track)
        if notes == 0:
            continue
        drums = channels == {9}
        out.append({"index": index, "name": name or "Track %d" % index, "notes": notes, "drums": drums,
                    "instrument": "drums" if drums else (_GM_FAMILIES[program // 8] if program is not None else None)})
    return out


def _keep_tracks(midi_path, keep, out_path):
    """Writes the MIDI file with only the tracks in `keep` (indices among
    its tracks) and those without notes."""
    with open(midi_path, "rb") as f:
        fmt, division, chunks = _chunks(f.read())
    tracks = [c for kind, c in chunks if kind == b"MTrk"]
    chosen = [t for i, t in enumerate(tracks) if i in keep or _track_info(t)[1] == 0]
    out = b"MThd" + (6).to_bytes(4, "big") + (1 if len(chosen) > 1 else fmt).to_bytes(2, "big") \
        + len(chosen).to_bytes(2, "big") + division.to_bytes(2, "big")
    for t in chosen:
        out += b"MTrk" + len(t).to_bytes(4, "big") + t
    with open(out_path, "wb") as f:
        f.write(out)


def midi_to_ly(midi_path, duration_quant=None, start_quant=None, key=None,
               tuplets=None, absolute=False, explicit_durations=False,
               text_lyrics=False, tracks=None):
    """Converts a MIDI file to LilyPond text with midi2ly.

    duration_quant / start_quant: quantise note durations / starts to this
      note value, e.g. "16"; tuplets: allowed tuplet durations, e.g.
      ["8*2/3"]; key: "sharps:minor", e.g. "-3:1" for C minor (3 flats);
      absolute: absolute pitches instead of \\relative; tracks: the indices
      (from midi_tracks) of the tracks to import, None for all.
    """
    with tempfile.TemporaryDirectory() as tmp:
        out = os.path.join(tmp, "out.ly")
        if tracks is not None:
            if not tracks:
                raise ValueError("no tracks chosen")
            chosen = os.path.join(tmp, "chosen.mid")
            _keep_tracks(midi_path, set(tracks), chosen)
            midi_path = chosen
        argv = ["midi2ly", "--output", out]
        if duration_quant:
            argv += ["--duration-quant", str(duration_quant)]
        if start_quant:
            argv += ["--start-quant", str(start_quant)]
        if key:
            argv += ["--key", str(key)]
        for t in tuplets or []:
            argv += ["--allow-tuplet", str(t)]
        if absolute:
            argv.append("--absolute-pitches")
        if explicit_durations:
            argv.append("--explicit-durations")
        if text_lyrics:
            argv.append("--text-lyrics")
        argv.append(midi_path)
        ok, log = _run("midi2ly", argv)
        if not ok or not os.path.exists(out):
            raise RuntimeError("midi2ly failed: " + log)
        with open(out, encoding="utf-8") as f:
            return f.read(), log


def musicxml_to_ly(path, absolute=False, keep_layout=False):
    """Converts a MusicXML file (.musicxml / .xml, or compressed .mxl) to
    LilyPond text with musicxml2ly. Returns (text, log).

    absolute: absolute pitches instead of \\relative; keep_layout: keep the
    file's page size, margins and system and page breaks (made for another
    page than the tablet's, so dropped by default)."""
    with open(path, "rb") as f:
        compressed = f.read(2) == b"PK"  # a zip: .mxl
    with tempfile.TemporaryDirectory() as tmp:
        out = os.path.join(tmp, "out.ly")
        argv = ["musicxml2ly", "--output", out, "--absolute" if absolute else "--relative"]
        if compressed:
            argv.append("--compressed")
        if not keep_layout:
            argv += ["--no-page-layout", "--no-system-breaks", "--no-page-breaks"]
        argv.append(path)
        ok, log = _run("musicxml2ly", argv)
        if not ok or not os.path.exists(out):
            raise RuntimeError("musicxml2ly failed: " + log)
        with open(out, encoding="utf-8") as f:
            return f.read(), log


def convert_ly(text, to_version=None):
    """Updates LilyPond input to the current (or given) syntax with
    convert-ly. Returns (text, log)."""
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "in.ly")
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
        argv = ["convert-ly", "--edit"]  # the backup goes in the temp dir
        if to_version:
            argv += ["--to", to_version]
        argv.append(path)
        ok, log = _run("convert_ly", argv)
        if not ok:
            raise RuntimeError("convert-ly failed: " + log)
        with open(path, encoding="utf-8") as f:
            return f.read(), log


def call(request_json):
    req = json.loads(request_json)
    op = req.pop("op")
    try:
        if op == "midi2ly":
            text, log = midi_to_ly(req.pop("path"), **req)
        elif op == "midi_tracks":
            return json.dumps({"tracks": midi_tracks(req["path"])})
        elif op == "musicxml2ly":
            text, log = musicxml_to_ly(req.pop("path"), **req)
        elif op == "convert_ly":
            text, log = convert_ly(req["text"], req.get("to"))
        else:
            return json.dumps({"error": "unknown op " + op})
        return json.dumps({"text": text, "log": log})
    except Exception as e:
        return json.dumps({"error": "%s: %s" % (type(e).__name__, e)})
