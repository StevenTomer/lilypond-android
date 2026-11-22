\version "2.26.0"
\header { title = "Hello from Android" composer = "LilyPond" }
melody = \relative c'' { \clef treble \key g \major \time 3/4
  g4 b8( a) g4 | d'2 c8 b | a4 g fis | g2. \bar "|." }
text = \lyricmode { Twin -- kle, lit -- tle star, how I won -- der }
\score {
  <<
    \new ChordNames \chordmode { g2. g2 c4 d2. g2. }
    \new Voice = "m" \melody
    \new Lyrics \lyricsto "m" \text
  >>
  \layout { }
}
\markup \italic { Set in TeX Gyre Schola on a tablet. }
