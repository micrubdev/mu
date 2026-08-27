(ns mu.pattern-props
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [mu.pattern :as p]
            [mu.transform :as x]))

;; ---- generators -------------------------------------------------------

(def gen-pure-leaf
  (gen/fmap p/pure (gen/elements [:a :b :c :d])))

(def gen-lifted-leaf
  "Raw scalars handed straight to a concatenation. This is the only safe
  home for unlifted values: `fast` and `rev` do not lift, so a raw
  keyword cannot be a bare leaf, but a concatenation of raw keywords is
  a Pattern and is safe anywhere."
  (gen/fmap #(apply p/fastcat %)
            (gen/vector (gen/elements [:a :b :c :d]) 1 3)))

(def gen-chord-leaf
  "A stack of note-bearing events sharing one whole -- the shape `chord`
  produces, and the only thing `arp` actually acts on. Without this in
  the generator, `arp` would pass every keyword leaf straight through
  and the laws would not exercise it at all."
  (gen/fmap (fn [ns]
              (p/pat (fn [sp]
                       (mapcat (fn [ev]
                                 (for [n ns] (assoc ev :value {:note n})))
                               (p/query (p/pure nil) sp)))))
            (gen/vector-distinct (gen/choose 48 72) {:min-elements 2
                                                     :max-elements 4})))

(def gen-leaf
  (gen/one-of [gen-pure-leaf gen-lifted-leaf gen-chord-leaf]))

(def gen-pattern
  (gen/recursive-gen
    (fn [inner]
      (gen/one-of
        [(gen/fmap #(apply p/fastcat %) (gen/vector inner 1 3))
         (gen/fmap #(apply p/slowcat %) (gen/vector inner 1 3))
         (gen/fmap #(apply p/stack %)   (gen/vector inner 1 2))
         (gen/fmap (fn [[n q]] (p/fast n q))
                   (gen/tuple (gen/elements [1 2 3]) inner))
         (gen/fmap p/rev inner)
         (gen/fmap (fn [[k n q]] (x/euclid k n q))
                   (gen/tuple (gen/choose 0 5) (gen/choose 0 8) inner))
         (gen/fmap (fn [[t q]] (x/off t p/rev q))
                   (gen/tuple (gen/elements [1/4 1/3 1/2]) inner))
         (gen/fmap #(x/superimpose p/rev %) inner)
         (gen/fmap (fn [[m q]] (x/arp m q))
                   (gen/tuple (gen/elements [:up :down :updown :downup]) inner))
         (gen/fmap (fn [[n q]] (x/iter n q))
                   (gen/tuple (gen/choose 0 4) inner))
         (gen/fmap (fn [[n t q]] (x/stut n 0.5 t q))
                   (gen/tuple (gen/choose 1 3) (gen/elements [1/4 1/3]) inner))
         ;; small generation counts only -- the point is that whatever
         ;; lsys produces obeys the laws, not that it produces a lot
         (gen/fmap (fn [[n q]] (p/stack (x/lsys {0 [0 1] 1 [0]} [0] n) q))
                   (gen/tuple (gen/choose 0 4) inner))
         (gen/fmap (fn [[k n a b]] (x/euclid-full k n a b))
                   (gen/tuple (gen/choose 0 5) (gen/choose 0 8) inner inner))]))
    gen-leaf))

(def gen-cycle (gen/choose -4 8))

(defn- norm
  "Canonical form for comparison: order-insensitive, timing-exact."
  [evs]
  (set (map (juxt :whole :part :value) evs)))

;; ---- laws -------------------------------------------------------------

(defspec fast-1-is-identity 100
  (prop/for-all [q gen-pattern, c gen-cycle]
    (= (norm (p/query q [c (inc c)]))
       (norm (p/query (p/fast 1 q) [c (inc c)])))))

(defspec rev-is-its-own-inverse 100
  (prop/for-all [q gen-pattern, c gen-cycle]
    (= (norm (p/query q [c (inc c)]))
       (norm (p/query (p/rev (p/rev q)) [c (inc c)])))))

(defspec fast-and-slow-cancel 100
  (prop/for-all [q gen-pattern, c gen-cycle, n (gen/elements [2 3 4])]
    (= (norm (p/query q [c (inc c)]))
       (norm (p/query (p/fast n (p/slow n q)) [c (inc c)])))))

(defspec nothing-escapes-the-queried-span 200
  (prop/for-all [q gen-pattern, c gen-cycle]
    (let [[b e] [c (inc c)]]
      (every? (fn [{[pb pe] :part}] (and (>= pb b) (<= pe e)))
              (p/query q [b e])))))

(defspec querying-is-deterministic 100
  (prop/for-all [q gen-pattern, c gen-cycle]
    (= (norm (p/query q [c (inc c)]))
       (norm (p/query q [c (inc c)])))))

(defspec splitting-a-query-is-the-same-as-not-splitting 300
  ;; THE load-bearing law. Querying [a c] must equal querying [a b]
  ;; then [b c] for any cycle-aligned b.
  (prop/for-all [q gen-pattern, c gen-cycle, n (gen/choose 1 4)]
    (let [a c, z (+ c n)]
      (= (norm (p/query q [a z]))
         (norm (mapcat #(p/query q [% (inc %)]) (range a z)))))))

(defspec onsets-are-never-duplicated-across-adjacent-queries 200
  ;; A note must trigger exactly once no matter how the span is carved.
  (prop/for-all [q gen-pattern, c gen-cycle]
    (let [whole-cycle (filter p/onset? (p/query q [c (inc c)]))
          halves      (filter p/onset?
                              (concat (p/query q [c (+ c 1/2)])
                                      (p/query q [(+ c 1/2) (inc c)])))]
      (= (count whole-cycle) (count halves)))))
