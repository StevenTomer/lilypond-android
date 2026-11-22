%% Engrave only a window of the music (lilyengine fast preview).
%% LILYENGINE_WINDOW="start end": moments in whole notes, e.g. "199 203" or "797/4 815/4".
#(define lilyengine:window
   (let ((w (getenv "LILYENGINE_WINDOW")))
     (and w (map (lambda (x) (inexact->exact (string->number x)))
                 (string-split w #\space)))))

#(define (lilyengine:skip len)
   (make-music 'SkipMusic 'duration
     (ly:make-duration 0 0 (numerator len) (denominator len))))

#(define (lilyengine:set-skip on)
   (context-spec-music (make-property-set 'skipTypesetting on) 'Score))

%% Like LilyPond's own showFirstLength/showLastLength (music-functions.scm):
%% the music is cut off at the end of the window, so nothing after it is
%% even interpreted, and typesetting is off until the window starts.
#(define (lilyengine:window-music music)
   (if (not lilyengine:window)
       music
       (let* ((len (ly:music-length music))
              (start (car lilyengine:window))
              ;; never past the end of the music (no empty bars added)
              (whole-end (min (cadr lilyengine:window) (ly:moment-main len)))
              ;; Cut a hair before the window's end: where a note held across
              ;; that bar line is split by Completion_heads_engraver (midi2ly's
              ;; output uses it), the tied second half would start on the
              ;; score's last column, and LilyPond crashes ("bounds of this
              ;; piece aren't breakable"; showFirstLength crashes the same way).
              ;; So the music stops just before the bar line, and the held note
              ;; simply overhangs the end.
              (end (if (< whole-end (ly:moment-main len))
                       (max (- whole-end 1/1024) (+ start 1/1024))
                       whole-end)))
         (if (ly:moment<? (ly:make-moment end) len)
             (set! (ly:music-property music 'length) (ly:make-moment end)))
         (make-simultaneous-music
          (list
           (make-sequential-music
            (append
             (if (> start 0)
                 (list (lilyengine:set-skip #t) (lilyengine:skip start))
                 '())
             (list
              (lilyengine:set-skip #f)
              (lilyengine:skip (max (- end start) 1/1024)))))
           music)))))

#(define toplevel-music-handler
   (let ((next toplevel-music-handler))
     (lambda (music) (next (lilyengine:window-music music)))))

#(define toplevel-score-handler
   (let ((next toplevel-score-handler))
     (lambda (score)
       (if lilyengine:window
           (let ((new (ly:make-score (lilyengine:window-music (ly:score-music score)))))
             ;; Layout only: MIDI of the window would hold only its notes, and
             ;; LilyPond's performers fail on music cut off like this.
             (for-each (lambda (def)
                         (if (not (eq? (ly:output-def-lookup def 'output-def-kind #f) 'midi))
                             (ly:score-add-output-def! new def)))
                       (ly:score-output-defs score))
             (let ((h (ly:score-header score))) (if (module? h) (ly:score-set-header! new h)))
             (next new))
           (next score)))))
