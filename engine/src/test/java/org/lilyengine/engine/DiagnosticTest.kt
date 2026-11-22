package org.lilyengine.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticTest {
    @Test
    fun parsesErrorsAndWarningsForInputFileOnly() {
        val log = """
            GNU LilyPond 2.26.0 (running Guile 3.0)
            Processing `/cache/job/score.ly'
            Parsing...
            /cache/job/score.ly:3:10: error: syntax error, unexpected '}'
            /cache/job/score.ly:5:2: warning: barcheck failed at: 1/4
            /data/share/lilypond/2.26.0/ly/init.ly:65:2: error: Guile signaled an error
            fatal error: failed files: "/cache/job/score.ly"
        """.trimIndent()
        val d = Diagnostic.parse(log, "/cache/job/score.ly")
        assertEquals(2, d.size)
        assertEquals(Diagnostic(Diagnostic.Severity.ERROR, 3, 10, "syntax error, unexpected '}'"), d[0])
        assertEquals(Diagnostic.Severity.WARNING, d[1].severity)
        assertEquals(5, d[1].line)
    }
}
