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
              (end (min (cadr lilyengine:window) (ly:moment-main len))))
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
              (lilyengine:skip (max (- end start) 1/64))
              ;; a moment of skipping at the end, so the skip engraver sees
              ;; typesetting stop (as showFirstLength does)
              (lilyengine:set-skip #t)
              (make-grace-music (make-music 'SkipMusic 'duration (ly:make-duration -10 0)))
              (lilyengine:set-skip #f))))
           music)))))

#(define toplevel-music-handler
   (let ((next toplevel-music-handler))
     (lambda (music) (next (lilyengine:window-music music)))))

#(define toplevel-score-handler
   (let ((next toplevel-score-handler))
     (lambda (score)
       (if lilyengine:window
           (let ((new (ly:make-score (lilyengine:window-music (ly:score-music score)))))
             (for-each (lambda (def) (ly:score-add-output-def! new def)) (ly:score-output-defs score))
             (let ((h (ly:score-header score))) (if (module? h) (ly:score-set-header! new h)))
             (next new))
           (next score)))))
