%% lilyengine/scoremap.ly: write a JSON "score map" next to the PDF.
%%
%% Loaded with -dinclude-settings=<this file>; the output path comes from
%% the LILYENGINE_SCOREMAP environment variable (no output if unset).
%%
%% For every printed grob the editor cares about, the map records the
%% page, its bounding box in page coordinates, the source location of the
%% music that caused it, which Voice and Staff it belongs to, the musical
%% moment, and (for notes) pitch, duration and staff position. Staff
%% symbols give the staff-line positions of every system.
%%
%% How: an engraver in the Score context remembers each grob's Voice,
%% Staff and moment while engraving. At output time the PostScript backend
%% calls `grob-cause` with each grob and its absolute page offset (that is
%% how point-and-click links are made); a wrapper around it records the
%% grob. Nothing in LilyPond is patched.

#(use-modules (srfi srfi-1))

#(define lilyengine:scoremap-path (getenv "LILYENGINE_SCOREMAP"))

%% grob -> (voice-context staff-context moment)
#(define lilyengine:grob-info (make-weak-key-hash-table 4096))
%% context -> small integer id, stable for this run
#(define lilyengine:context-ids (make-weak-key-hash-table 64))
#(define lilyengine:next-context-id 0)
#(define lilyengine:records '())
#(define lilyengine:page 0)

#(define (lilyengine:context-number ctx)
   (if (not ctx)
       #f
       (or (hashq-ref lilyengine:context-ids ctx)
           (begin
             (set! lilyengine:next-context-id (1+ lilyengine:next-context-id))
             (hashq-set! lilyengine:context-ids ctx lilyengine:next-context-id)
             lilyengine:next-context-id))))

#(define (lilyengine:collector context)
   (make-engraver
    (acknowledgers
     ((grob-interface engraver grob source-engraver)
      (let ((ctx (ly:translator-context source-engraver)))
        (hashq-set! lilyengine:grob-info grob
                    (list (ly:context-find ctx 'Voice)
                          (ly:context-find ctx 'Staff)
                          (ly:context-current-moment ctx))))))))

\layout {
  \context { \Score \consists #lilyengine:collector }
}

#(define lilyengine:wanted
   '(NoteHead Rest MultiMeasureRest Stem Accidental Dots Script
     DynamicText Hairpin Slur PhrasingSlur Tie Beam BarLine
     StaffSymbol Clef ClefModifier KeySignature TimeSignature LyricText ChordName))

%% JSON writer. Written straight to the output port, without `format`
%% (slow in Guile): a full page of piano music has thousands of grobs.
#(define lilyengine:json-special (char-set #\" #\\ #\newline #\return #\tab))

#(define (lilyengine:write-json-string s port)
   (write-char #\" port)
   (if (string-index s lilyengine:json-special)
       (string-for-each
        (lambda (c)
          (case c
            ((#\") (display "\\\"" port))
            ((#\\) (display "\\\\" port))
            ((#\newline) (display "\\n" port))
            ((#\return) (display "\\r" port))
            ((#\tab) (display "\\t" port))
            (else (write-char c port))))
        s)
       (display s port))
   (write-char #\" port))

#(define (lilyengine:write-json v port)
   (cond ((eq? v #t) (display "true" port))
         ((eq? v #f) (display "null" port))
         ((null? v) (display "[]" port))
         ((string? v) (lilyengine:write-json-string v port))
         ((symbol? v) (lilyengine:write-json-string (symbol->string v) port))
         ((number? v)
          (cond ((and (exact? v) (integer? v)) (display v port))
                ((or (inf? v) (nan? v)) (display "null" port))
                (else (display (/ (round (* (exact->inexact v) 10000.0)) 10000.0) port))))
         ((and (pair? v) (pair? (car v)) (symbol? (caar v)))
          (write-char #\{ port)
          (let loop ((kvs v) (first? #t))
            (if (pair? kvs)
                (begin
                  (if (not first?) (write-char #\, port))
                  (lilyengine:write-json-string (symbol->string (caar kvs)) port)
                  (write-char #\: port)
                  (lilyengine:write-json (cdar kvs) port)
                  (loop (cdr kvs) #f))))
          (write-char #\} port))
         ((list? v)
          (write-char #\[ port)
          (let loop ((xs v) (first? #t))
            (if (pair? xs)
                (begin
                  (if (not first?) (write-char #\, port))
                  (lilyengine:write-json (car xs) port)
                  (loop (cdr xs) #f))))
          (write-char #\] port))
         (else (lilyengine:write-json-string (object->string v) port))))

%% Absolute LilyPond pitch name in Dutch note names, e.g. fis'' or bes,
#(define (lilyengine:pitch-name p)
   (let* ((names #("c" "d" "e" "f" "g" "a" "b"))
          (alt (ly:pitch-alteration p))
          (suffix (cond ((= alt 1) "isis") ((= alt 1/2) "is")
                        ((= alt -1/2) "es") ((= alt -1) "eses") (else "")))
          (base (vector-ref names (ly:pitch-notename p)))
          (name (cond ((and (string=? base "e") (string-prefix? "es" suffix))
                       (string-append "e" (substring suffix 1)))
                      ((and (string=? base "a") (string-prefix? "es" suffix))
                       (string-append "a" (substring suffix 1)))
                      (else (string-append base suffix))))
          (o (ly:pitch-octave p)))
     (string-append name
                    (cond ((>= o 0) (make-string (1+ o) #\'))
                          ((= o -1) "")
                          (else (make-string (- (1+ o)) #\,))))))

#(define (lilyengine:moment->json m)
   (and (ly:moment? m)
        (let ((main (ly:moment-main m)) (grace (ly:moment-grace m)))
          `((main . ,(number->string main))
            (grace . ,(number->string grace))))))

#(define (lilyengine:record! offset grob)
   (let ((name (grob::name grob)))
     (if (and (memq name lilyengine:wanted)
              ;; Empty grobs (e.g. a key signature with no accidentals)
              ;; have no box; point-and-click skips them too.
              (not (interval-empty? (ly:grob-extent grob grob X)))
              (not (interval-empty? (ly:grob-extent grob grob Y))))
         (let* ((info (hashq-ref lilyengine:grob-info grob '(#f #f #f)))
                (voice (first info))
                (staff (second info))
                (cause (ly:grob-property grob 'cause))
                (event (and (ly:stream-event? cause) cause))
                (origin (and event (ly:event-property event 'origin #f)))
                (loc (and (ly:input-location? origin)
                          (ly:input-file-line-char-column origin)))
                (pitch (and event (ly:event-property event 'pitch #f)))
                (duration (and event (ly:event-property event 'duration #f)))
                (x (ly:grob-extent grob grob X))
                (y (ly:grob-extent grob grob Y))
                (fields
                 `((book . ,lilyengine:book)
                   (page . ,lilyengine:page)
                   (type . ,name)
                   (bbox . ,(list (+ (car offset) (car x)) (+ (cdr offset) (car y))
                                  (+ (car offset) (cdr x)) (+ (cdr offset) (cdr y))))
                   (voice . ,(lilyengine:context-number voice))
                   (voiceId . ,(and voice (ly:context-id voice)))
                   (staff . ,(lilyengine:context-number staff))
                   (staffId . ,(and staff (ly:context-id staff)))
                   (moment . ,(lilyengine:moment->json (third info)))
                   (origin . ,(and loc `((file . ,(first loc)) (line . ,(second loc))
                                         (char . ,(third loc)) (column . ,(fourth loc)))))
                   (pitch . ,(and (ly:pitch? pitch)
                                  `((notename . ,(ly:pitch-notename pitch))
                                    (alteration . ,(number->string (ly:pitch-alteration pitch)))
                                    (octave . ,(ly:pitch-octave pitch))
                                    (name . ,(lilyengine:pitch-name pitch)))))
                   (duration . ,(and (ly:duration? duration) (ly:duration->string duration)))
                   (staffPosition . ,(and (memq name '(NoteHead Rest Clef))
                                          (ly:grob-property grob 'staff-position #f)))
                   ;; A clef's symbol ("clefs.G"), and its 8 or 15 above (+) or below (-).
                   (clefGlyph . ,(and (eq? name 'Clef) (ly:grob-property grob 'glyph-name #f)))
                   (clefOctave
                    . ,(and (eq? name 'ClefModifier)
                            (let ((text (markup->string (ly:grob-property grob 'text "")))
                                  (dir (ly:grob-property grob 'direction DOWN)))
                              (* (if (eqv? dir UP) 1 -1) (if (string-contains text "15") 2 1)))))
                   (linePositions . ,(and (eq? name 'StaffSymbol)
                                          (ly:grob-property grob 'line-positions #f)))
                   (staffSpace . ,(and (eq? name 'StaffSymbol)
                                       (ly:staff-symbol-staff-space grob)))
                   ;; A key signature's sharps and flats: step (0 = c) and alteration.
                   (keyAlterations
                    . ,(and (eq? name 'KeySignature)
                            (map (lambda (entry)
                                   (let ((step (if (pair? (car entry)) (cdar entry) (car entry))))
                                     `((step . ,step) (alteration . ,(number->string (cdr entry))))))
                                 (ly:grob-property grob 'alteration-alist '())))))))
           (set! lilyengine:records (cons fields lilyengine:records))))))

%% Wrap the PostScript backend's grob-cause.
#(if lilyengine:scoremap-path
     (let* ((m (resolve-module '(lily output-ps)))
            (alist (module-ref m 'stencil-dispatch-alist))
            (orig (assq-ref alist 'grob-cause)))
       ;; Replace the entry in place: LilyPond 2.24 lets the *last* entry
       ;; for a key win, 2.26 the first, so prepending only works on 2.26.
       (module-set! m 'stencil-dispatch-alist
                    (map (lambda (entry)
                           (if (eq? (car entry) 'grob-cause)
                               (cons 'grob-cause
                                     (lambda (offset grob)
                                       (lilyengine:record! offset grob)
                                       (orig offset grob)))
                               entry))
                         alist))))

%% Count pages: the backend writes "%%Page: n n" before each page.
#(if lilyengine:scoremap-path
     (let* ((m (resolve-module '(lily)))
            (orig (module-ref m 'ly:outputter-dump-string)))
       (module-set! m 'ly:outputter-dump-string
                    (lambda (outputter str)
                      (if (string-prefix? "%%Page: " str)
                          (set! lilyengine:page (1+ lilyengine:page)))
                      (orig outputter str)))))

%% Write the map after each book is output (a file can contain several
%% \book blocks, each with its own PDF); records carry their book number.
#(define lilyengine:book 0)

#(define (lilyengine:write-map!)
   (let ((paper $defaultpaper))
     (call-with-output-file lilyengine:scoremap-path
       (lambda (port)
         (lilyengine:write-json
          `((version . 1)
            (paperWidth . ,(ly:output-def-lookup paper 'paper-width))
            (paperHeight . ,(ly:output-def-lookup paper 'paper-height))
            (outputScale . ,(ly:output-def-lookup paper 'output-scale))
            (grobs . ,(reverse lilyengine:records)))
          port)))))

#(if lilyengine:scoremap-path
     (set! toplevel-book-handler
           (let ((next toplevel-book-handler))
             (lambda (book)
               (set! lilyengine:book (1+ lilyengine:book))
               (set! lilyengine:page 0)
               (next book)
               (lilyengine:write-map!)))))
