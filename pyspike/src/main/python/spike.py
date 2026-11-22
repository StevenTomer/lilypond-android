"""python-ly on Android: the operations the editor needs, on the two-voice example."""
import sys
import time

POLY = r"""\version "2.26.0"
\relative c'' {
  \key g \major \time 4/4
  << { c4 e g e } \\ { c,2 g' } >> |
  <g b d>2 r4 fis8 g |
}
"""


def run():
    out = []
    t0 = time.time()
    import ly
    import ly.pkginfo
    import ly.document
    import ly.pitch.transpose
    import ly.pitch.rel2abs
    import ly.lex
    out.append("Python %s, python-ly %s, import %.0f ms" % (sys.version.split()[0], ly.pkginfo.version, (time.time() - t0) * 1000))

    # 1. Tokens (syntax highlighting)
    t = time.time()
    doc = ly.document.Document(POLY)
    tokens = [(type(tok).__name__, str(tok)) for block in doc for tok in doc.tokens(block)]
    out.append("tokens: %d in %.0f ms, e.g. %s" % (len(tokens), (time.time() - t) * 1000, tokens[8:13]))

    # 2. Relative -> absolute (does each \\ voice use the pitch before << ?)
    t = time.time()
    doc = ly.document.Document(POLY)
    cursor = ly.document.Cursor(doc)
    ly.pitch.rel2abs.rel2abs(cursor)
    out.append("rel2abs in %.0f ms:\n%s" % ((time.time() - t) * 1000, doc.plaintext()))

    # 3. Transpose up a major second
    doc = ly.document.Document(POLY)
    cursor = ly.document.Cursor(doc)
    transposer = ly.pitch.transpose.Transposer(ly.pitch.Pitch(0, 0, 0), ly.pitch.Pitch(1, 0, 0))
    ly.pitch.transpose.transpose(cursor, transposer)
    out.append("transposed c -> d:\n%s" % doc.plaintext())

    # 4. Music tree with positions (for mapping score-map origins to text)
    import ly.music
    doc = ly.document.Document(POLY)
    tree = ly.music.document(doc)
    notes = [(n.__class__.__name__, n.position, n.token) for n in tree.iter_music() if n.__class__.__name__ in ("Note", "Chord")]
    out.append("music tree notes (class, position, token): %s" % notes[:8])
    return "\n\n".join(out)
