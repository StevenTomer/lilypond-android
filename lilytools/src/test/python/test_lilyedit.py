"""Host tests for lilyedit: python3 -m unittest (python-ly on PYTHONPATH)."""
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "main", "python"))
import lilyedit as e

POLY = """\\relative c'' {
  << { c4 e g e } \\\\ { c,2 g' } >> |
  <g b d>2 r4 fis8 g |
}
"""


def absolute(text):
    return [(n.note, float(n.alter), n.octave) for n in e.notes(text)]


class Notes(unittest.TestCase):
    def test_relative_two_voices_match_lilypond(self):
        names = [ (n.note, n.octave) for n in e.notes(POLY)][1:]  # skip the \relative start pitch
        # c'' e'' g'' e'' | c' g' | g' b' d'' | fis' g'  (python-ly: c' = octave 1)
        self.assertEqual(names, [(0, 2), (2, 2), (4, 2), (2, 2), (0, 1), (4, 1),
                                 (4, 1), (6, 1), (1, 2), (3, 1), (4, 1)])


class SetPitch(unittest.TestCase):
    def pos(self, text, token, nth=0):
        i = -1
        for _ in range(nth + 1):
            i = text.index(token, i + 1)
        return i

    def test_relative_keeps_following_notes(self):
        src = "\\relative { c'4 d e f | g1 }"
        before = absolute(src)
        out = e.set_pitch(src, self.pos(src, "d"), 4, 0, 2)  # d' -> g''
        after = absolute(out)
        self.assertEqual(after[1], (4, 0.0, 2))
        self.assertEqual(after[:1] + after[2:], before[:1] + before[2:])
        self.assertEqual(out, "\\relative { c'4 g'' e, f | g1 }")

    def test_absolute_mode_changes_only_the_note(self):
        src = "{ c'4 d' e' }"
        out = e.set_pitch(src, src.index("d'"), 3, 0.5, 1)
        self.assertEqual(out, "{ c'4 fis' e' }")

    def test_voice_two_of_backslash_construct(self):
        # c,2 (voice 2) is c'; make it a' — voice 1 untouched, g' after it still g'.
        out = e.set_pitch(POLY, POLY.index("c,2"), 5, 0, 1)
        self.assertIn("<< { c4 e g e } \\\\ {", out)
        b, a = absolute(POLY), absolute(out)
        self.assertEqual(a[5], (5, 0.0, 1))
        self.assertEqual(a[:5] + a[6:], b[:5] + b[6:])

    def test_chord_note(self):
        out = e.set_pitch(POLY, POLY.index("b d>"), 0, 0, 2)  # b' -> c''
        b, a = absolute(POLY), absolute(out)
        self.assertEqual(a[8], (0, 0.0, 2))
        self.assertEqual(a[:8] + a[9:], b[:8] + b[9:])
        self.assertIn("<g c d>2", out)

    def test_formatting_kept(self):
        src = "\\relative c'' {\n  % melody\n  c4   d  e\n}\n"
        out = e.set_pitch(src, src.index("d  e"), 2, -0.5, 2)
        self.assertEqual(out, "\\relative c'' {\n  % melody\n  c4   es  e\n}\n")


class Durations(unittest.TestCase):
    def test_next_note_keeps_its_implicit_duration(self):
        src = "{ c'4 d' e' f'2 }"
        self.assertEqual(e.set_duration(src, src.index("d'"), "8"), "{ c'4 d'8 e'4 f'2 }")

    def test_replace_written_duration(self):
        src = "{ c'4 d'8. e'16 }"
        self.assertEqual(e.set_duration(src, src.index("d'"), "2"), "{ c'4 d'2 e'16 }")

    def test_chord_and_rest(self):
        src = "{ <c' e'>2 r4 g'4 }"
        self.assertEqual(e.set_duration(src, src.index("e'"), "4"), "{ <c' e'>4 r4 g'4 }")
        self.assertEqual(e.set_duration(src, src.index("r4"), "8"), "{ <c' e'>2 r8 g'4 }")

    def test_same_duration_needs_no_extra(self):
        src = "{ c'8 d' e' }"
        self.assertEqual(e.set_duration(src, src.index("d'"), "8"), "{ c'8 d'8 e' }")


class Delete(unittest.TestCase):
    def test_relative_delete_keeps_octaves_and_durations(self):
        src = "\\relative { c''2 g4 e' d }"
        out = e.delete(src, src.index("g4"))
        b = absolute(src)
        self.assertEqual(absolute(out), b[:1] + b[2:])
        self.assertEqual(out, "\\relative { c''2 e4 d }")  # e after c'' is e''

    def test_delete_from_chord(self):
        src = "{ <c' e' g'>2 }"
        self.assertEqual(e.delete(src, src.index("e'")), "{ <c' g'>2 }")


class Bridge(unittest.TestCase):
    def test_call(self):
        r = json.loads(e.call(json.dumps({"op": "set_duration", "text": "{ c'4 d' }", "pos": 6, "duration": "2"})))
        self.assertEqual(r["text"], "{ c'4 d'2 }")
        r = json.loads(e.call(json.dumps({"op": "set_pitch", "text": "{ c'4 }", "pos": 99, "note": 0, "alter": 0, "octave": 1})))
        self.assertIn("error", r)
        r = json.loads(e.call(json.dumps({"op": "notes", "text": "{ fis'4 }"})))
        self.assertEqual(r["notes"][0]["alter"], 0.5)

    def test_transpose(self):
        src = "\\relative c' { c4 e g }"
        out = e.transpose(src, 0, -1, "c", "d")
        self.assertEqual([(n, o) for n, a, o in absolute(out)][1:], [(1, 1), (3, 1), (5, 1)])  # d' fis' a'
        self.assertIn("d4 fis a", out)


if __name__ == "__main__":
    unittest.main()
