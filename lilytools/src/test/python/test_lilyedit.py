"""Host tests for lilyedit: python3 -m unittest (python-ly on PYTHONPATH)."""
import json
from fractions import Fraction
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


class Step(unittest.TestCase):
    def test_follows_the_key(self):
        src = "\\relative { \\key g \\major e'4 a }"
        self.assertEqual(e.step(src, src.index("e'"), 1), "\\relative { \\key g \\major fis'4 a }")
        src = "{ \\key f \\major a'4 }"
        self.assertEqual(e.step(src, src.index("a'"), 1), "{ \\key f \\major bes'4 }")

    def test_down_and_octave_wrap(self):
        src = "{ \\key g \\major g'4 c'4 }"
        self.assertEqual(e.step(src, src.index("g'"), -1), "{ \\key g \\major fis'4 c'4 }")
        self.assertEqual(e.step(src, src.index("c'4 }"), -1), "{ \\key g \\major g'4 b4 }")

    def test_modes(self):
        self.assertEqual(e.scale_alter((5, 0, "minor"), 3), 0)       # a minor: f
        self.assertEqual(e.scale_alter((2, 0, "major"), 4), 0.5)     # e major: gis
        self.assertEqual(e.scale_alter((1, 0, "dorian"), 6), 0)      # d dorian: b
        self.assertEqual(e.scale_alter((6, -0.5, "major"), 2), -0.5) # bes major: es

    def test_latest_key_before_the_note(self):
        src = "global = { \\key d \\major }\n{ \\global b'4 c'' \\key c \\major b' }"
        out = e.step(src, src.index("b'4"), 1)
        self.assertIn("{ \\global cis''4 c''", out)
        out = e.step(src, src.rindex("b'"), 1)
        self.assertTrue(out.endswith("\\key c \\major c'' }"), out)

    def test_no_key_is_c_major(self):
        self.assertEqual(e.step("{ e'4 }", 2, 1), "{ f'4 }")


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


class Spell(unittest.TestCase):
    def spelled(self, midi_key, key_text):
        n, a, o = e.spell(midi_key, e.key_at(key_text + " c", len(key_text) + 1))
        return "cdefgab"[n] + {-0.5: "es", 0: "", 0.5: "is"}[float(a)], o

    def test_key_decides_sharps_or_flats(self):
        self.assertEqual(self.spelled(61, "\\key g \\major"), ("cis", 1))
        self.assertEqual(self.spelled(61, "\\key f \\major"), ("des", 1))
        self.assertEqual(self.spelled(70, ""), ("bes", 1))  # C major
        self.assertEqual(self.spelled(66, ""), ("fis", 1))

    def test_scale_notes_and_leading_tone(self):
        self.assertEqual(self.spelled(65, "\\key d \\major"), ("f", 1))
        self.assertEqual(self.spelled(66, "\\key d \\major"), ("fis", 1))
        self.assertEqual(self.spelled(68, "\\key a \\minor"), ("gis", 1))
        self.assertEqual(self.spelled(63, "\\key c \\minor"), ("ees", 1))

    def test_octave_follows_the_written_note(self):
        self.assertEqual(self.spelled(60, "\\key cis \\major"), ("bis", 0))  # sounds as c'
        self.assertEqual(self.spelled(59, "\\key ges \\major"), ("ces", 1))  # sounds as b
        self.assertEqual(self.spelled(36, ""), ("c", -1))


class Insert(unittest.TestCase):
    REL = "\\relative c'' { c4 e g e | c1 }"

    def test_after_a_note_in_relative(self):
        at = self.REL.index(" e ") + 1
        out, start = e.insert(self.REL, at, [64], None, True)  # e' below the e''
        self.assertEqual(out, "\\relative c'' { c4 e e, g' e | c1 }")
        self.assertEqual(out[start:start + 2], "e,")
        self.assertEqual(absolute(out)[4:], absolute(self.REL)[3:])  # g e c unchanged (index 0: the start pitch)

    def test_new_duration_keeps_the_next_one(self):
        at = self.REL.index(" e ") + 1
        out, _ = e.insert(self.REL, at, [48], "8", True)
        self.assertEqual(out, "\\relative c'' { c4 e c,,8 g'''4 e | c1 }")

    def test_chord_before_a_note(self):
        out, start = e.insert(self.REL, self.REL.index("c1"), [67, 60, 64])
        self.assertEqual(out, "\\relative c'' { c4 e g e | <c, e g> c'1 }")
        self.assertEqual(out[start], "<")

    def test_rest_at_the_end(self):
        out, _ = e.insert(self.REL, len(self.REL) - 2, [], "2")
        self.assertEqual(out, "\\relative c'' { c4 e g e | c1 r2 }")

    def test_voice_two_leaves_voice_one(self):
        out, _ = e.insert(POLY, POLY.index("g' }"), [55], "4", True)
        self.assertIn("{ c4 e g e } \\\\ { c,2 g' g,4 }", out)
        b, a = absolute(POLY), absolute(out)
        self.assertEqual(a[:7], b[:7])
        self.assertEqual(a[7], (4, 0.0, 0))  # the new g
        self.assertEqual(a[8:], b[7:])

    def test_absolute_music(self):
        src = "{ c'4 d' }"
        out, _ = e.insert(src, src.index("d'"), [76], None, True)
        self.assertEqual(out, "{ c'4 d' e'' }")

    def test_after_a_chord_note_means_after_the_chord(self):
        src = "{ <c' e'>2 g'2 }"
        out, _ = e.insert(src, src.index("e'"), [62], "4", True)
        self.assertEqual(out, "{ <c' e'>2 d'4 g'2 }")

    def test_cursor_inside_a_note_inserts_after_it(self):
        src = "{ fis'8 g' }"
        out, _ = e.insert(src, src.index("is"), [64])
        self.assertEqual(out, "{ fis'8 e' g' }")

    def test_through_call(self):
        reply = json.loads(e.call(json.dumps(
            {"op": "insert", "text": self.REL, "pos": self.REL.index("c1"), "keys": [72]})))
        self.assertEqual(reply["text"][reply["start"]:reply["start"] + 1], "c")



class Marks(unittest.TestCase):
    def test_articulation_on_and_off(self):
        src = "\\relative { c'4 d e }"
        on = e.toggle(src, src.index("d"), "staccato")
        self.assertEqual(on, "\\relative { c'4 d-. e }")
        self.assertEqual(e.toggle(on, on.index("d"), "staccato"), src)

    def test_any_direction_counts(self):
        src = "{ c'4^. d'_> }"
        self.assertEqual(e.marks(src, 2), ["staccato"])
        self.assertEqual(e.toggle(src, src.index("d'"), "accent"), "{ c'4^. d' }")

    def test_accent_and_decrescendo_after_a_note(self):
        # '>' here is not the end of a chord
        src = "{ c'4->\\> d' }"
        self.assertEqual(e.marks(src, 2), ["accent", "decresc"])
        self.assertEqual(e.delete(src, 2), "{ d' }")

    def test_marks_after_a_space_belong_to_the_note(self):
        src = "{ b'4 -. c''4 }"  # as musicxml2ly writes them
        self.assertEqual(e.marks(src, 2), ["staccato"])
        self.assertEqual(e.toggle(src, 2, "staccato"), "{ b'4 c''4 }")
        self.assertEqual(e.delete(src, 2), "{ c''4 }")

    def test_marks_go_on_the_chord(self):
        src = "{ <c' e' g'>2 r }"
        out = e.toggle(src, src.index("e'"), "fermata")
        self.assertEqual(out, "{ <c' e' g'>2\\fermata r }")

    def test_one_dynamic_per_note(self):
        src = "{ c'4\\p-. d' }"
        self.assertEqual(e.toggle(src, 2, "f"), "{ c'4-.\\f d' }")
        self.assertEqual(e.toggle(src, 2, "p"), "{ c'4-. d' }")

    def test_hairpins_replace_each_other(self):
        src = "{ c'4\\< d' e'\\! }"
        self.assertEqual(e.toggle(src, 2, "decresc"), "{ c'4\\> d' e'\\! }")

    def test_slurs_and_ties(self):
        src = "{ c'8 d' e'~ e' }"
        out = e.toggle(src, 2, "slur-start")
        out = e.toggle(out, out.index("d'"), "slur-end")
        self.assertEqual(out, "{ c'8( d') e'~ e' }")
        self.assertEqual(e.toggle(out, out.index("e'"), "tie"), "{ c'8( d') e' e' }")
        self.assertEqual(e.marks(out, out.index("e'")), ["tie"])

    def test_second_voice_only(self):
        src = "<< { c'4 d' } \\\\ { e2 } >>"
        out = e.toggle(src, src.index("e2"), "tenuto")
        self.assertEqual(out, "<< { c'4 d' } \\\\ { e2-- } >>")

    def test_rest_and_unknown(self):
        self.assertEqual(e.toggle("{ r2 }", 2, "fermata"), "{ r2\\fermata }")
        self.assertIn("error", json.loads(e.call(json.dumps({"op": "toggle", "text": "{ c }", "pos": 2, "what": "x"}))))

    def test_through_call(self):
        reply = json.loads(e.call(json.dumps({"op": "marks", "text": "{ c4-.( d) }", "pos": 2})))
        self.assertEqual(reply["marks"], ["staccato", "slur-start"])



class Ranges(unittest.TestCase):
    SRC = "\\relative c'' { << { c4 e g e } \\\\ { c,2 g' } >> | <g b d>2 r4 fis8 g | }"

    def at(self, *tokens):
        return [self.SRC.index(t) for t in tokens]

    def test_step_keeps_the_other_voice_and_finds_the_notes_again(self):
        out, pos = e.multi(self.SRC, self.at("c4", "e g", "g e", "e }"), "step", 1)
        self.assertEqual(out, "\\relative c'' { << { d4 f a f } \\\\ { c,2 g' } >> | <g b d>2 r4 fis8 g | }")
        self.assertEqual([out[p] for p in pos], ["d", "f", "a", "f"])
        # voice 2 and the bar after sound as before
        self.assertEqual(absolute(out)[-7:], absolute(self.SRC)[-7:])

    def test_duration_is_written_once(self):
        out, _ = e.multi(self.SRC, self.at("c4", "e g", "g e", "e }"), "duration", "8")
        self.assertIn("{ c8 e g e }", out)
        out, _ = e.multi(self.SRC, self.at("e g", "g e"), "duration", "8")
        self.assertIn("{ c4 e8 g e4 }", out)

    def test_dot_all_or_none(self):
        out, pos = e.multi(self.SRC, self.at("c4", "e g"), "dot")
        self.assertIn("{ c4. e g4 e }", out)
        back, _ = e.multi(out, pos, "dot")
        self.assertIn("{ c4 e g4 e }", back)  # g4 was written by the first edit, and is right

    def test_marks_add_to_all_then_remove_from_all(self):
        some = e.toggle(self.SRC, self.SRC.index("e g"), "staccato")
        pos = [some.index("c4"), some.index("e-."), some.index("g e")]
        out, pos = e.multi(some, pos, "toggle", "staccato")
        self.assertIn("{ c4-. e-. g-. e }", out)
        out, _ = e.multi(out, pos, "toggle", "staccato")
        self.assertIn("{ c4 e g e }", out)

    def test_chord_notes_count_once(self):
        out, _ = e.multi(self.SRC, self.at("<g", "b d", "d>"), "toggle", "fermata")
        self.assertIn("<g b d>2\\fermata r4", out)

    def test_rests_are_skipped_by_pitch_edits(self):
        out, pos = e.multi(self.SRC, self.at("r4", "fis"), "alter", 0)
        self.assertIn("r4 f8 g", out)
        self.assertEqual(out[pos[0]], "r")

    def test_delete(self):
        out, pos = e.multi(self.SRC, self.at("r4", "fis"), "delete")
        self.assertEqual(pos, [])
        self.assertIn("<g b d>2 g8 |", out)

    def test_slur_in_each_voice_and_off_again(self):
        positions = self.at("c4", "e }", "c,2", "g' }")
        pairs = [(positions[0], positions[1]), (positions[2], positions[3])]
        out, pos = e.span(self.SRC, pairs, "slur", positions)
        self.assertIn("{ c4( e g e) } \\\\ { c,2( g') }", out)
        back, _ = e.span(out, [(pos[0], pos[1]), (pos[2], pos[3])], "slur")
        self.assertEqual(back, self.SRC)

    def test_hairpin(self):
        out, _ = e.span(self.SRC, [tuple(self.at("<g", "g |"))], "cresc")
        self.assertIn("<g b d>2\\< r4 fis8 g\\! |", out)

    def test_one_note_is_not_a_span(self):
        reply = json.loads(e.call(json.dumps({"op": "span", "text": self.SRC, "kind": "slur",
                                              "pairs": [[self.SRC.index("c4"), self.SRC.index("c4")]]})))
        self.assertIn("error", reply)



class Voices(unittest.TestCase):
    SRC = "\\relative c'' { \\key g \\major b4 c d2 | e4 d c b | a1 }"

    def test_durations(self):
        self.assertEqual(e._dur_value("4.."), Fraction(7, 16))
        self.assertEqual(e._dur_value("1*3/4"), Fraction(3, 4))
        self.assertEqual(e._durations_for(Fraction(7, 8)), ["2.", "8"])
        self.assertEqual(e._durations_for(Fraction(5, 8)), ["2", "8"])

    def test_add_voice_with_rests_as_long_as_the_passage(self):
        out, start = e.add_voice(self.SRC, [self.SRC.index("b4"), self.SRC.index("d2")])
        self.assertEqual(out, "\\relative c'' { \\key g \\major << { b4 c d2 } \\\\ { r1 } >> | e4 d c b | a1 }")
        self.assertEqual(out[start:start + 2], "r1")
        self.assertEqual(absolute(out), absolute(self.SRC))

    def test_durations_stay_right_around_the_new_voice(self):
        src = "\\relative c'' { b8 c d e f2 | g }"
        out, _ = e.add_voice(src, [src.index("c d"), src.index("e f")])
        self.assertEqual(out, "\\relative c'' { b8 << { c8 d e } \\\\ { r4. } >> f2 | g }")

    def test_across_voices_is_refused(self):
        src = "<< { c'4 d' } \\\\ { e2 } >>"
        with self.assertRaises(ValueError):
            e.add_voice(src, [src.index("d'"), src.index("e2")])

    def test_entry_fills_the_rests(self):
        out, start = e.add_voice(self.SRC, [self.SRC.index("b4"), self.SRC.index("d2")])
        for key, d in [(55, "4"), (57, "4"), (54, "2")]:
            out, start = e.fill(out, start, [key], d)
        self.assertIn("{ b4 c d2 } \\\\ { g,,4 a fis2 } >> | e''4 d c b", out)
        # full: nothing left to fill, so entry inserts as usual
        self.assertIsNone(e.fill(out, start, [55], "4"))
        # every other note sounds as before
        self.assertEqual(absolute(e.remove_voice(out, out.index("g,,"))), absolute(self.SRC))

    def test_fill_through_insert(self):
        out, start = e.add_voice(self.SRC, [self.SRC.index("b4"), self.SRC.index("d2")])
        reply = json.loads(e.call(json.dumps({"op": "insert", "text": out, "pos": start, "keys": [55],
                                              "duration": "2", "after": True, "fill": True})))
        self.assertIn("{ g,,2 r2 }", reply["text"])

    def test_remove_either_voice(self):
        out, _ = e.add_voice(self.SRC, [self.SRC.index("b4"), self.SRC.index("d2")])
        out, start = e.fill(out, out.index("r1"), [55], "1")
        self.assertEqual(e.remove_voice(out, out.index("g,,")), self.SRC)
        self.assertEqual(e.remove_voice(out, out.index("b4")),
                         "\\relative c'' { \\key g \\major g,1 | e''4 d c b | a1 }")  # the same g, now after c

    def test_remove_voice_outside_voices_is_refused(self):
        with self.assertRaises(ValueError):
            e.remove_voice(self.SRC, self.SRC.index("a1"))



class StaffSteps(unittest.TestCase):
    def test_key_signature_gives_the_accidental(self):
        src = "\\relative c'' { \\key g \\major g4 a b c }"
        self.assertEqual(e.keys_for_steps(src, src.index("c }"), [(3, 1), (3, 0), (0, 0)]), [78, 66, 60])
        src = "{ \\key f \\major c'4 }"
        self.assertEqual(e.keys_for_steps(src, len(src) - 1, [(6, 0)]), [70])  # b flat

    def test_insert_by_step(self):
        src = "\\relative c'' { \\key g \\major g4 a b c }"
        reply = json.loads(e.call(json.dumps({"op": "insert", "text": src, "pos": src.index("c }"),
                                              "steps": [[3, 1]], "after": True})))
        self.assertEqual(reply["text"], "\\relative c'' { \\key g \\major g4 a b c fis }")
        self.assertEqual(reply["keys"], [78])



class VoiceLengths(unittest.TestCase):
    SRC = "\\relative c'' { << { c4 e g e } \\\\ { c,2 } >> d1 | << { a2 } \\\\ { f4 g } >> c }"

    def test_finds_the_short_voice(self):
        found = e.voice_lengths(self.SRC)
        self.assertEqual([(self.SRC[a:a + 2], lengths) for a, _, lengths in found], [("<<", [1, Fraction(1, 2)])])

    def test_pad_with_rests_keeps_the_music_after(self):
        out = e.pad_voices(self.SRC, self.SRC.index("c,2"))
        self.assertEqual(out, "\\relative c'' { << { c4 e g e } \\\\ { c,2 r2 } >> d1 | << { a2 } \\\\ { f4 g } >> c }")
        self.assertEqual(e.voice_lengths(out), [])
        self.assertEqual(absolute(out), absolute(self.SRC))

    def test_duration_after_the_passage_is_kept(self):
        src = "\\relative c'' { << { c4 e g e } \\\\ { c,2 } >> d | c }"
        self.assertIn(">> d2 | c }", e.pad_voices(src, src.index("c,2")))

    def test_three_voices(self):
        src = "{ << { c'2 d' } \\\\ { e1 } \\\\ { g4 } >> }"
        out = e.pad_voices(src, src.index("g4"))
        self.assertEqual(out, "{ << { c'2 d' } \\\\ { e1 } \\\\ { g4 r2. } >> }")

    def test_tuplets_are_not_checked(self):
        src = "{ << { c'4 d' e'2 } \\\\ { \\tuplet 3/2 { c'4 d' e' } } >> }"
        self.assertEqual(e.voice_lengths(src), [])

    def test_through_call(self):
        reply = json.loads(e.call(json.dumps({"op": "voice_lengths", "text": self.SRC})))
        self.assertEqual(reply["passages"][0]["lengths"], ["1", "1/2"])



class ChordsAndSpelling(unittest.TestCase):
    SRC = "\\relative c'' { \\key g \\major c4 <e g> b2 | d1 }"

    def test_note_becomes_a_chord(self):
        out, start = e.add_to_chord(self.SRC, self.SRC.index("c4"), [76])
        self.assertIn("<c e>4 <e g> b2", out)
        self.assertEqual(out[start], "<")
        self.assertEqual(absolute(e.multi(out, [start], "delete")[0]), absolute(e.delete(self.SRC, self.SRC.index("c4"))))

    def test_chord_grows_and_the_rest_sounds_the_same(self):
        out, _ = e.add_to_chord(self.SRC, self.SRC.index("<e"), [72])
        added = [k for k in absolute(out) if k not in absolute(self.SRC)]
        self.assertEqual(added, [])  # c'' is already there; the others stay put
        self.assertEqual(len(absolute(out)), len(absolute(self.SRC)) + 1)
        self.assertEqual(absolute(out)[-2:], absolute(self.SRC)[-2:])

    def test_spelled_for_the_key(self):
        out, _ = e.add_to_chord(self.SRC, self.SRC.index("b2"), [66])
        self.assertIn("<b fis", out)

    def test_rest_refused(self):
        with self.assertRaises(ValueError):
            e.add_to_chord("{ r4 }", 2, [60])

    def test_respell(self):
        for before, after in [("cis'4", "des'4"), ("des'4", "cis'4"), ("e'4", "fes'4"),
                              ("c'4", "bis4"), ("b4", "ces'4"), ("f'4", "eis'4")]:
            self.assertEqual(e.respell("{ %s }" % before, 2), "{ %s }" % after)
        with self.assertRaises(ValueError):
            e.respell("{ d'4 }", 2)

    def test_respell_in_relative_keeps_the_pitches_after(self):
        src = "\\relative c'' { cis4 d e }"
        out = e.respell(src, src.index("cis"))
        self.assertEqual(absolute(out)[2:], absolute(src)[2:])
        self.assertIn("des", out)

    def test_respell_a_range_skips_what_has_no_other_spelling(self):
        src = "{ cis'4 d' ees' }"
        out, _ = e.multi(src, [2, src.index("d'"), src.index("ees")], "respell")
        self.assertEqual(out, "{ des'4 d' dis' }")



class TransposeParts(unittest.TestCase):
    def test_part_of_relative_music_leaves_the_rest_sounding_the_same(self):
        src = "\\relative c'' { c4 d e f | g a b c }"
        out = e.transpose(src, src.index("c4"), src.index("| g"), "c", "d")
        self.assertEqual(absolute(out)[1:5], [(1, 0, 2), (2, 0, 2), (3, 0.5, 2), (4, 0, 2)])
        self.assertEqual(absolute(out)[5:], absolute(src)[5:])

    def test_down_a_tone(self):
        src = "{ c'4 e' }"
        out = e.transpose(src, 0, -1, "c", "bes,")
        self.assertEqual(out, "{ bes4 d' }")

    def test_whole_text_with_key(self):
        src = "\\relative c' { \\key c \\major c4 e g c }"
        out = e.transpose(src, 0, -1, "c", "d")
        self.assertIn("\\key d \\major", out)



class Lyrics(unittest.TestCase):
    PIANO = ("upper = \\relative c'' { b4 c d2 | e4 d c b }\n"
             "lower = \\relative c { g2 d' | c b }\n"
             "\\score { \\new PianoStaff << \\new Staff \\upper \\new Staff \\lower >> }")

    def test_plain_text_round_trip(self):
        plain = "Twin-kle twin-kle lit-tle star_ _ \"3rd\""
        lily = e.plain_to_lyrics(plain)
        self.assertEqual(lily, 'Twin -- kle twin -- kle lit -- tle star __ _ "\\"3rd\\""')
        self.assertEqual(e.lyrics_to_plain(lily), plain)

    def test_first_verse_goes_after_the_melody(self):
        pos = self.PIANO.index("c d2")
        out, p = e.set_verse(self.PIANO, pos, 0, "Twin-kle twin-kle star")
        self.assertIn("e4 d c b } \\addlyrics { Twin -- kle twin -- kle star }\nlower", out)
        self.assertEqual(p, pos)
        self.assertEqual(e.verses(out, pos), ["Twin-kle twin-kle star"])

    def test_verses_are_added_changed_and_removed(self):
        pos = self.PIANO.index("c d2")
        out, _ = e.set_verse(self.PIANO, pos, 0, "one")
        out, _ = e.set_verse(out, pos, 1, "two")
        out, _ = e.set_verse(out, pos, 0, "first")
        self.assertEqual(e.verses(out, pos), ["first", "two"])
        out, _ = e.set_verse(out, pos, 0, "")
        self.assertEqual(e.verses(out, pos), ["two"])
        with self.assertRaises(ValueError):
            e.set_verse(out, pos, 3, "x")

    def test_a_voice_of_a_passage_keeps_its_setting(self):
        src = "\\relative c'' { << { b4 c d2 } \\\\ { g,2 fis } >> | g1 }"
        pos = src.index("fis")
        out, p = e.set_verse(src, pos, 0, "Ah oh")
        self.assertEqual(out, "\\relative c'' { << { b4 c d2 } \\\\ { \\voiceTwo g,2 fis } \\addlyrics { Ah oh } >> | g1 }")
        self.assertEqual(out[p:p + 3], "fis")
        self.assertEqual(absolute(out), absolute(src))

    def test_inside_a_score_block(self):
        src = "\\score { \\new Staff \\relative c'' { c4 d e f } \\layout { } }"
        out, _ = e.set_verse(src, src.index("d e"), 0, "la la la la")
        self.assertIn("{ c4 d e f } \\addlyrics { la la la la } \\layout", out)



class LyricChecks(unittest.TestCase):
    SRC = "mel = \\relative c'' { c4( d) e~ e r f <g b> a } \\addlyrics { one two -- three four five } \\addlyrics { a b }"

    def test_slurs_ties_chords_and_rests(self):
        # c( d) one syllable, e~ e one, r none, f, <g b>, a: five notes to sing
        self.assertEqual(e.verse_check(self.SRC, self.SRC.index("e~")), [(5, 5), (2, 5)])

    def test_word_under_the_pen(self):
        self.assertEqual(e.verse_at(self.SRC, self.SRC.index("three")), (self.SRC.index("{") + 1, 0, 1))
        self.assertEqual(e.verse_at(self.SRC, self.SRC.index("five")), (self.SRC.index("{") + 1, 0, 3))
        self.assertEqual(e.verse_at(self.SRC, self.SRC.index("a b") + 2), (self.SRC.index("{") + 1, 1, 1))
        self.assertIsNone(e.verse_at(self.SRC, self.SRC.index("c4")))



class LyricsTo(unittest.TestCase):
    # As musicxml2ly writes them: the melody and its lyrics in variables.
    SRC = ('melody = \\relative c\'\' { c4 d e f }\n'
           'melodyLyrics = \\lyricmode { one two three four }\n'
           '\\score { << \\new Staff << \\context Voice = "mel" { \\voiceOne \\melody } >>\n'
           '  \\new Lyrics \\lyricsto "mel" \\melodyLyrics >> }')

    def test_reads_and_edits_the_lyrics_variable(self):
        pos = self.SRC.index("c4 d")
        self.assertEqual(e.verses(self.SRC, pos), ["one two three four"])
        out, p = e.set_verse(self.SRC, pos, 0, "Hel-lo dear world")
        self.assertIn("melodyLyrics = \\lyricmode { Hel -- lo dear world }", out)
        self.assertEqual(out[p:p + 4], "c4 d")

    def test_another_verse_is_another_lyrics_line(self):
        pos = self.SRC.index("c4 d")
        out, _ = e.set_verse(self.SRC, pos, 1, "sec-ond verse")
        self.assertIn('\\lyricsto "mel" \\melodyLyrics \\new Lyrics \\lyricsto "mel" { sec -- ond verse }', out)
        self.assertNotIn("\\addlyrics", out)
        self.assertEqual(e.verses(out, pos), ["one two three four", "sec-ond verse"])

    def test_named_voice_without_lyrics(self):
        src = self.SRC.replace('\n  \\new Lyrics \\lyricsto "mel" \\melodyLyrics', '')
        out, _ = e.set_verse(src, src.index("c4 d"), 0, "brand new")
        self.assertIn('\\melody } \\new Lyrics \\lyricsto "mel" { brand new } >>', out)

    def test_emptying_keeps_the_lyrics_context(self):
        out, _ = e.set_verse(self.SRC, self.SRC.index("c4 d"), 0, "")
        self.assertIn("melodyLyrics = \\lyricmode { }", out)



class Recording(unittest.TestCase):
    SRC = "\\relative c'' { \\key g \\major \\time 4/4 g4 | d1 }"

    def test_quantized_spelled_with_chords_and_rests(self):
        played = [(0.03, 0.9, 72), (1.02, 1.8, 74), (1.97, 2.95, 76),
                  (3.0, 5.1, 67), (3.02, 5.0, 71), (6.0, 6.9, 66)]
        out, a, b = e.record(self.SRC, self.SRC.index("g4"), played, "1/4")
        self.assertEqual(out[a:b], "c4 d4 e4 <g, b>2 r4 fis4")
        # the d1 after sounds as before
        self.assertEqual(absolute(out)[-1], absolute(self.SRC)[-1])

    def test_ties_across_the_bar_line(self):
        src = "{ \\time 3/4 c'4 }"
        out, a, b = e.record(src, src.index("c"), [(1.0, 3.0, 64), (3.0, 4.0, 67)], "1/4")
        self.assertEqual(out[a:b], "r4 e'4~ e'4 g'4")

    def test_coarser_grid(self):
        src = "{ c'4 }"
        out, a, b = e.record(src, 2, [(0.0, 0.4, 62), (0.55, 1.0, 64)], "1/4", grid="8")
        self.assertEqual(out[a:b], "d'8 e'8")

    def test_time_signature_in_effect(self):
        src = "{ \\time 6/8 c4 \\time 2/4 d }"
        self.assertEqual(e.time_at(src, src.index("d }")), Fraction(1, 2))
        self.assertEqual(e.time_at("{ c4 }", 3), Fraction(1))



class NewScores(unittest.TestCase):
    def test_every_template_has_its_parts_and_bars(self):
        for t in e.TEMPLATES:
            text = e.new_score(t, "T", "C", "bes", "minor", "6/8", None, 2)
            self.assertIn("\\key bes \\minor", text)
            self.assertIn("r2. |\n  r2. \\bar", text)  # two bars of 6/8 rests per part
            self.assertEqual(text.count("{"), text.count("}"), t)

    def test_header_and_tempo(self):
        text = e.new_score("piano", 'Say "hi"', "", "c", "major", "4/4", 120, 1)
        self.assertIn('title = "Say \\"hi\\""', text)
        self.assertNotIn("composer", text)
        self.assertIn("\\tempo 4 = 120", text)



class Settings(unittest.TestCase):
    SRC = ('\\version "2.26.0"\n\\header { title = "Point and click" }\nupper = { c\'1 }\n'
           '\\score { \\new Staff \\upper \\header { piece = "x" } }')

    def test_read(self):
        got = e.settings(self.SRC)
        self.assertEqual(got["title"], "Point and click")
        self.assertIsNone(got["composer"])
        self.assertIsNone(got["paper"])

    def test_set_and_remove(self):
        out = e.set_settings(self.SRC, {"title": "Minuet", "composer": 'J. "S." Bach', "paper": "letter",
                                        "landscape": True, "staff_size": 18})
        self.assertIn('\\header { title = "Minuet" composer = "J. \\"S.\\" Bach" }', out)
        self.assertIn('#(set-default-paper-size "letterlandscape")\n', out)
        self.assertIn("#(set-global-staff-size 18)\n", out)
        self.assertEqual(e.settings(out)["composer"], 'J. "S." Bach')
        self.assertIn('piece = "x"', out)  # the score's own header is left alone
        back = e.set_settings(out, {"composer": "", "paper": None, "staff_size": None})
        self.assertEqual(back, self.SRC.replace("Point and click", "Minuet"))

    def test_header_added_when_missing(self):
        self.assertEqual(e.set_settings('\\version "2.26.0"\n{ c4 }', {"title": "New"}),
                         '\\version "2.26.0"\n\\header {\n  title = "New"\n}\n{ c4 }')



class CopyPaste(unittest.TestCase):
    SRC = "\\relative c'' { c4 d e8 f g4 | a1 }\nlower = { c4 d e f }"

    def test_copy_writes_out_the_first_duration(self):
        fragment, pitches = e.copy(self.SRC, [self.SRC.index("d e8"), self.SRC.index("f g4")])
        self.assertEqual(fragment, "d4 e8 f")
        self.assertEqual(pitches, [(1, 0, 2), (2, 0, 2), (3, 0, 2)])

    def test_paste_into_relative_and_absolute_keeps_the_pitches(self):
        fragment, pitches = e.copy(self.SRC, [self.SRC.index("d e8"), self.SRC.index("f g4")])
        out, a, b = e.paste(self.SRC, self.SRC.index("a1"), fragment, pitches)
        self.assertEqual(out[a:b], "d,4 e8 f")
        out, a, b = e.paste(self.SRC, self.SRC.index("f }"), fragment, pitches)
        self.assertEqual(out[a:b], "d''4 e''8 f''")

    def test_across_voices_is_refused(self):
        src = "<< { c'4 d' } \\\\ { e2 } >>"
        with self.assertRaises(ValueError):
            e.copy(src, [src.index("d'"), src.index("e2")])


if __name__ == "__main__":
    unittest.main()
