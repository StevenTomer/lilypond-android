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


def midi_to_ly(midi_path, duration_quant=None, start_quant=None, key=None,
               tuplets=None, absolute=False, explicit_durations=False,
               text_lyrics=False):
    """Converts a MIDI file to LilyPond text with midi2ly.

    duration_quant / start_quant: quantise note durations / starts to this
      note value, e.g. "16"; tuplets: allowed tuplet durations, e.g.
      ["8*2/3"]; key: "sharps:minor", e.g. "-3:1" for C minor (3 flats);
      absolute: absolute pitches instead of \\relative.
    """
    with tempfile.TemporaryDirectory() as tmp:
        out = os.path.join(tmp, "out.ly")
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
        elif op == "musicxml2ly":
            text, log = musicxml_to_ly(req.pop("path"), **req)
        elif op == "convert_ly":
            text, log = convert_ly(req["text"], req.get("to"))
        else:
            return json.dumps({"error": "unknown op " + op})
        return json.dumps({"text": text, "log": log})
    except Exception as e:
        return json.dumps({"error": "%s: %s" % (type(e).__name__, e)})
