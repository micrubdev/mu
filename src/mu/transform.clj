(ns mu.transform
  "The derived vocabulary: combinators built ON the algebra rather than
  part of it.

  `mu.pattern` is the algebra -- the Pattern record, the query model,
  the primitives, the linear time transforms and the applicative. This
  namespace is everything expressed in terms of those: cycle-indexed
  transforms, euclidean rhythms, the degrade family.

  The split keeps each file holding one idea. Purity boundary is the
  same as `mu.pattern`'s: no wall clock, no MIDI, no mutable state."
  (:require [mu.pattern :as p]
            [mu.time :as t]))

(defn every
  "Apply f to the pattern on every nth cycle, starting at cycle 0."
  [n f p]
  (if (<= n 0)
    p
    (let [transformed (f p)]
      (p/pat (fn [sp]
               (mapcat (fn [[b e]]
                         (let [c (t/floor-cycle b)]
                           (p/query (if (zero? (mod c n)) transformed p) [b e])))
                       (t/split-cycles sp)))))))

(defn superimpose
  "Stack f applied to the pattern against the untouched original."
  [f p]
  (p/stack p (f p)))

(defn off
  "Stack a copy shifted t cycles later, with f applied to that copy.

  t is in cycles and rational, like every other time value here."
  [t f p]
  (p/stack p (f (p/late t p))))

(defn- bjorklund
  "Bjorklund's algorithm: distribute k onsets as evenly as possible over
  n steps. Returns a vector of booleans.

  Only ever called with 0 < k < n -- `euclid` handles the degenerate
  cases before this point, which is also what guarantees progress:
  both groups are non-empty, so m >= 1 and the remainder shrinks."
  [k n]
  (loop [a (repeat k [true])
         b (repeat (- n k) [false])]
    (if (< (count b) 2)
      (vec (mapcat identity (concat a b)))
      (let [m (min (count a) (count b))]
        (recur (mapv into (take m a) (take m b))
               (concat (drop m a) (drop m b)))))))

(defn euclid
  "Distribute k onsets as evenly as possible over n steps of one cycle,
  playing p at each onset and resting otherwise.

  E(3,8) is the tresillo `x..x..x.`; E(5,8) the cinquillo `x.xx.xx.`.
  `rot` rotates the step vector left, and is taken mod n."
  ([k n p] (euclid k n 0 p))
  ([k n rot p]
   (cond
     (or (<= n 0) (<= k 0)) p/silence
     (>= k n)               (p/fast n p)
     :else
     (let [steps (bjorklund k n)
           r     (mod rot n)
           steps (concat (drop r steps) (take r steps))]
       (apply p/fastcat (map #(if % p p/silence) steps))))))

(defn degrade-by
  "Randomly drop a proportion `amt` of events (0.0 keeps all, 1.0 drops all)."
  [amt p]
  (p/pat (fn [sp]
           (filter #(>= (p/time-rand (first (:part %))) (double amt))
                   (p/query p sp)))))

(defn- undegrade-by
  "Keep exactly the events that `degrade-by` with the same amt drops."
  [amt p]
  (p/pat (fn [sp]
           (filter #(< (p/time-rand (first (:part %))) (double amt))
                   (p/query p sp)))))

(defn degrade
  "Randomly drop about half the events."
  [p]
  (degrade-by 0.5 p))

(defn sometimes-by
  "Apply f to a proportion `amt` of events, leaving the rest untouched.
  The two halves are complementary, so no event is lost or duplicated."
  [amt f p]
  (p/stack (degrade-by amt p)
           (f (undegrade-by amt p))))

(defn sometimes
  "Apply f to about half the events."
  [f p]
  (sometimes-by 0.5 f p))

(def ^:private arp-orders
  "How to walk a stack of n notes. Each returns a seq of indices.

  :updown and :downup turn WITHOUT repeating the note they turn on --
  otherwise a four-note run out of a triad lands on the top twice and
  the figure limps."
  {:up     (fn [n] (range n))
   :down   (fn [n] (reverse (range n)))
   :updown (fn [n] (concat (range n) (range (- n 2) 0 -1)))
   :downup (fn [n] (concat (reverse (range n)) (range 1 (dec n))))})

(defn- note-event?
  "Only note-bearing events form a stack. Everything else -- bare
  scalars, maps with no :note, continuous signals -- passes through, the
  same contract `mu.harmony/scale` and `chord` keep."
  [{:keys [whole value]}]
  (boolean (and whole (map? value) (contains? value :note))))

(defn- restack
  "The stack machinery `arp`, `strum` and `walk` share.

  Query a whole cycle at a time and clip, the way `rev` and `every` do.
  Grouping is the reason: a stack is discovered by which note-bearing
  events share a :whole IN THIS QUERY, so a narrower span could see
  fewer notes and re-time them differently. Anchoring on the containing
  cycle makes the grouping independent of how the span was carved.

  `f` is called with the cycle number and one stack -- two or more
  events sharing a :whole, sorted by :note then printed value (a total
  order, so unisons come out the same whether the query was split or
  not) -- and returns the events to replace it with."
  [f p]
  (p/pat
    (fn [sp]
      (mapcat
        (fn [[qb qe :as piece]]
          (let [c    (t/floor-cycle qb)
                evs  (p/query p [c (inc c)])
                {notes true others false} (group-by note-event? evs)
                done (mapcat
                       (fn [[_ evs]]
                         (if (< (count evs) 2)
                           evs
                           (f c (vec (sort-by (juxt (comp :note :value)
                                                    (comp pr-str :value))
                                              evs)))))
                       (group-by :whole notes))]
            (keep (fn [ev]
                    (when-let [part (t/sect (:part ev) piece)]
                      (assoc ev :part part)))
                  (concat others done))))
        (t/split-cycles sp)))))

(defn- reslot
  "Put the notes at `idxs` of a sorted stack into equal consecutive
  slots starting at the stack's whole, `len` cycles each."
  [sorted idxs len]
  (let [[wb _] (:whole (first sorted))]
    (keep-indexed
      (fn [i idx]
        (let [b    (+ wb (* i len))
              slot [b (+ b len)]
              part (t/sect slot (:part (nth sorted idx)))]
          (when part
            (assoc (nth sorted idx) :whole slot :part part))))
      idxs)))

(defn arp
  "Spread simultaneous notes across the span they share.

  Note-bearing events sharing an identical :whole are treated as one
  stack -- which is exactly what `mu.harmony/chord` produces -- sorted
  by :note and re-timed into equal slots across that whole. Each note
  becomes its own onset in its own slot, so an arpeggio triggers n
  times where the chord triggered once.

  Modes: :up, :down, :updown, :downup.

    (arp :up (chord 3 (notes 0 3 4)))

  A lone event is not a stack and passes through untouched, as does
  anything without a :note."
  [mode p]
  (let [order (or (arp-orders mode)
                  (throw (ex-info (str "mu: unknown arp mode " (pr-str mode)
                                       ". Known: :up, :down, :updown, :downup")
                                  {:mode mode})))]
    (restack (fn [_ sorted]
               (let [idxs    (vec (order (count sorted)))
                     [wb we] (:whole (first sorted))]
                 (reslot sorted idxs (/ (- we wb) (count idxs)))))
             p)))

(defn strum
  "Stagger a stack's onsets and let every note ring to the chord's end.

  Note i of n starts `i * spread / n` into the whole and holds to the
  whole's end, so `(strum 1/4 (chord 3 p))` brushes a triad over the
  first quarter of its span. Negative spread strums high to low. Unlike
  `arp`, nothing is cut short: the chord still sounds as a chord once
  the strum is through.

  textbeat's `maj$_`."
  [spread p]
  (restack (fn [_ sorted]
             (let [n       (count sorted)
                   [wb we] (:whole (first sorted))
                   step    (/ (abs spread) n)
                   order   (if (neg? spread) (reverse (range n)) (range n))]
               (keep-indexed
                 (fn [i idx]
                   (let [b     (+ wb (* i step))
                         whole [b we]
                         part  (t/sect whole (:part (nth sorted idx)))]
                     (when part
                       (assoc (nth sorted idx) :whole whole :part part))))
                 order)))
           p))

(defn walk
  "Play one note of each stack per cycle, low to high, coming round
  again after n cycles: on cycle c a stack sounds its (c mod n)-th note,
  wrapping past its size. Rhythm is untouched -- each stack keeps its
  own whole -- so a chord progression under `walk` becomes a single
  line tracing the chords.

  textbeat's `maj&4`; `walk` with n equal to the stack size is its `maj&`."
  [n p]
  (if (<= n 0)
    p
    (restack (fn [c sorted]
               [(nth sorted (mod (mod c n) (count sorted)))])
             p)))

(defn iter
  "Rotate the pattern one nth of a cycle further on each successive
  cycle, coming home after n cycles.

    (iter 4 (notes c4 d4 e4 f4))
    ;; cycle 0: c d e f   cycle 1: d e f c   cycle 2: e f c d ...

  n <= 1 is the identity."
  [n p]
  (if (<= n 1)
    p
    (p/pat (fn [sp]
             (mapcat (fn [[b e]]
                       (let [c (t/floor-cycle b)]
                         (p/query (p/early (/ (mod c n) n) p) [b e])))
                     (t/split-cycles sp))))))

(defn stut
  "n copies of the pattern, each `t` cycles later than the last, with
  :vel multiplied by `fb` each time.

    (stut 3 0.6 1/16 (notes c2))   ; a triplet echo, fading

  Copy 0 is the original. An event with no :vel is treated as 1.0, so
  the echoes fade from full velocity. n <= 1 is the original alone."
  [n fb t p]
  (if (<= n 1)
    p
    (let [layered (apply p/stack
                         (for [i (range n)]
                           (let [gain (Math/pow (double fb) i)]
                             (p/fmap (fn [v]
                                       (if (map? v)
                                         (assoc v :vel (* gain (double (:vel v 1.0))))
                                         v))
                                     (p/late (* i t) p)))))]
      ;; Anchor on the containing cycle, as `rev`, `every` and `arp` do.
      ;; `late` is not span-canonical -- querying [0 2] gives one part
      ;; [3/4 5/4] where querying [0 1] then [1 2] gives two -- so a
      ;; stack of shifted copies inherits that. Querying a cycle at a
      ;; time makes the result equal to the per-cycle decomposition by
      ;; construction, whatever `late` does underneath. Onsets and
      ;; wholes are identical either way; only the fragmenting differs.
      (p/pat (fn [sp]
               (mapcat #(p/query layered %) (t/split-cycles sp)))))))

(defn euclid-full
  "Like `euclid`, but plays `q` on the rests instead of resting.

    (euclid-full 3 8 kick snare)   ; kick on x..x..x., snare between

  `k <= 0` is q on every step; `k >= n` is p on every step."
  [k n p q]
  (cond
    (<= n 0)   p/silence
    (<= k 0)   (p/fast n q)
    (>= k n)   (p/fast n p)
    :else      (apply p/fastcat (map #(if % p q) (bjorklund k n)))))

;; ---- automation -----------------------------------------------------------
;;
;; A signal is continuous and never an onset, so the render cannot see
;; it. These sample one at a rate and turn the samples into discrete
;; control events -- {:cc n :val v}, {:bend v}, {:mod v} -- which the
;; render sends where a note-on would go. Automation is then pattern
;; data like everything else: `(play! :filter #'sweep)`, redefined on
;; the cycle edge.

(defn ctrl
  "Controller `n` following `sig`, sampled `rate` times per cycle.

    (ctrl 74 16 (slow 4 sine))   ; a four-bar filter sweep"
  [n rate sig]
  (p/with (p/fast rate (p/pure {:cc n})) :val sig))

(defn bend
  "Pitch bend following `sig` (-1.0 to 1.0), sampled `rate` times per cycle."
  [rate sig]
  (p/with (p/fast rate (p/pure {})) :bend sig))

(defn modw
  "The modulation wheel (cc 1) following `sig`, sampled `rate` times per
  cycle. `modw`, not `mod`, which is core's modulo."
  [rate sig]
  (p/with (p/fast rate (p/pure {})) :mod sig))

;; ---- song form ------------------------------------------------------------

(defn song
  "Sections in order, each played for a number of cycles, then round
  again:

    (song [:verse 8 verse] [:chorus 4 chorus] [:verse 8 verse])

  is twenty cycles long and loops. Each section sees its own cycle
  count from zero -- `every` inside the chorus counts chorus cycles, the
  same reasoning as `slowcat`'s offset -- and a section is any pattern,
  so a section can be a `song`: that is textbeat's callstack. The
  section table is kept on the pattern's metadata for `section-at`."
  [& sections]
  (when (empty? sections)
    (throw (ex-info "mu: song needs at least one [label cycles pattern] section" {})))
  (doseq [[label n _ :as sec] sections]
    (when-not (and (= 3 (count sec)) (integer? n) (pos? n))
      (throw (ex-info (str "mu: bad song section " (pr-str sec)
                           " -- want [label positive-cycles pattern]")
                      {:section sec :label label}))))
  (let [starts (vec (reductions + 0 (map second sections)))
        total  (peek starts)
        ;; The section containing cycle c and c's offset into it.
        locate (fn [c]
                 (let [cm (mod c total)
                       i  (dec (count (take-while #(<= % cm) starts)))]
                   [i (- cm (nth starts i))]))]
    (with-meta
      (p/pat (fn [sp]
               (mapcat
                 (fn [[b e]]
                   (let [c       (t/floor-cycle b)
                         [i off] (locate c)
                         pat     (nth (nth sections i) 2)
                         ;; Shift so the section sees cycle `off`: query
                         ;; it at (c - shift) and move the results back.
                         shift   (- c off)]
                     (p/query (p/late shift pat) [b e])))
                 (t/split-cycles sp))))
      {::sections (mapv (fn [[label n _]] [label n]) sections)
       ::locate   locate
       ::length   total})))

(defn song-length
  "How many cycles a `song` runs before it repeats."
  [s]
  (::length (meta s)))

(defn section-at
  "Which section of a `song` is playing at cycle `c`, as
  [label cycle-within-section]."
  [s c]
  (let [{::keys [sections locate]} (meta s)
        [i off] (locate c)]
    [(first (nth sections i)) off]))

(defn once
  "Play `p` on cycle 0 and nothing after -- a one-shot, textbeat's
  non-looping playback. Under `late` it fires on the cycle you say."
  [p]
  (p/pat (fn [sp]
           (mapcat (fn [[b e :as piece]]
                     (when (zero? (t/floor-cycle b)) (p/query p piece)))
                   (t/split-cycles sp)))))
