package org.lilyengine.harness

/** Two voices on one staff with `\\`, then a chord: the score-map test case. */
internal val POLY_SAMPLE = """
\version "2.26.0"
\relative c'' {
  \key g \major \time 4/4
  << { c4 e g e } \\ { c,2 g' } >> |
  <g b d>2 r4 fis8 g |
}
""".trimStart()

/** Enough music to keep LilyPond busy for several seconds (cancellation test). */
internal val HEAVY_SAMPLE = """
\version "2.26.0"
\new PianoStaff <<
  \new Staff \relative c'' { \repeat unfold 400 { << { c8 d e f } \\ { a,4 b } >> } }
  \new Staff \relative c { \clef bass \repeat unfold 400 { <c e g>2 } }
>>
""".trimStart()

/** A MusicXML score in the app's assets: two voices on one staff, a chord, a staccato, and a second part. */
internal const val MUSICXML_SAMPLE_ASSET = "samples/two-voices.musicxml"
