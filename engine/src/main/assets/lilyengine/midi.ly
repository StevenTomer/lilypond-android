%% lilyengine/midi.ly: give every score MIDI output.
%%
%% Loaded with -dinclude-settings=<this file>, before the user's file.
%% Every score that lacks a \midi block gets one. A score with no output
%% definitions at all also gets the default \layout, because adding only
%% \midi would suppress LilyPond's implicit layout. The user's file is not
%% changed.
%%
%% The hook is the top-level book handler: every score ends up in a book
%% (explicit \book, \bookpart, \score, or bare music), and it runs once
%% per book, just before the book is processed.

#(define (lilyengine:with-midi! score)
   (let ((kinds (map (lambda (def) (ly:output-def-lookup def 'output-def-kind #f))
                     (ly:score-output-defs score))))
     (if (not (memq 'midi kinds))
         (begin
           (if (null? kinds)
               (ly:score-add-output-def! score (ly:output-def-clone $defaultlayout)))
           (ly:score-add-output-def! score (ly:output-def-clone $defaultmidi))))))

#(define (lilyengine:book-with-midi! book)
   (for-each (lambda (item) (if (ly:score? item) (lilyengine:with-midi! item)))
             (ly:book-scores book))
   (for-each lilyengine:book-with-midi! (ly:book-book-parts book)))

#(define toplevel-book-handler
   (let ((next toplevel-book-handler))
     (lambda (book)
       (lilyengine:book-with-midi! book)
       (next book))))
