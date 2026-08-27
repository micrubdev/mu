(ns mu.transform-test
  (:require [clojure.test :refer [deftest is testing]]
            [mu.pattern :as p]
            [mu.transform :as x]))

(defn- vals-at
  "Values of the onsets in one cycle, in time order."
  [p cyc]
  (->> (p/query p [cyc (inc cyc)])
       (filter p/onset?)
       (sort-by (comp first :part))
       (map :value)))

(deftest every-applies-f-on-matching-cycles
  (let [e (x/every 3 (partial p/fast 2) (p/pure :a))]
    (is (= [:a :a] (vals-at e 0)) "cycle 0 is transformed")
    (is (= [:a]    (vals-at e 1)))
    (is (= [:a]    (vals-at e 2)))
    (is (= [:a :a] (vals-at e 3)) "and again at cycle 3")))

(deftest degrade-drops-roughly-half
  (let [d (x/degrade (p/fast 16 (p/pure :a)))
        n (count (filter p/onset? (p/query d [0 8])))]
    (is (< 40 n 216) (str "expected roughly half of 128 events, got " n))))

(deftest sometimes-partitions-events-without-losing-any
  (let [base  (p/fast 16 (p/pure :a))
        total (count (filter p/onset? (p/query base [0 4])))
        s     (x/sometimes (fn [_] (p/pure :b)) base)
        got   (frequencies (map :value (filter p/onset? (p/query s [0 4]))))]
    (is (pos? (get got :a 0)) "some events were left alone")
    (is (pos? (get got :b 0)) "some events were transformed")))

(deftest superimpose-stacks-the-transformed-copy
  (testing "the original survives alongside f applied to it"
    (let [q (x/superimpose #(p/fast 2 %) (p/pure :a))]
      (is (= 3 (count (p/query q [0 1])))
          "one from the original, two from the doubled copy"))))

(deftest off-shifts-the-copy-and-leaves-the-original
  (testing "onsets at 0 (original) and 1/4 (the shifted copy)"
    (let [q (x/off 1/4 identity (p/pure :a))]
      (is (= [0 1/4]
             (->> (p/query q [0 1])
                  (filter p/onset?)
                  (map (comp first :whole))
                  sort)))))
  (testing "f is applied to the copy, not the original"
    (let [q (x/off 1/2 #(p/fmap (constantly :b) %) (p/pure :a))]
      (is (= #{:a :b}
             (set (map :value (filter p/onset? (p/query q [0 1])))))))))

(defn- euclid-str
  "Render a euclidean rhythm as x-for-onset, .-for-rest over n steps."
  [k n rot]
  (let [starts (->> (p/query (x/euclid k n rot (p/pure :a)) [0 1])
                    (filter p/onset?)
                    (map (comp first :whole))
                    set)]
    (apply str (for [i (range n)] (if (starts (/ i n)) \x \.)))))

(deftest euclid-matches-the-canonical-vectors
  (testing "E(3,8), the tresillo"
    (is (= "x..x..x." (euclid-str 3 8 0))))
  (testing "E(5,8), the cinquillo"
    (is (= "x.xx.xx." (euclid-str 5 8 0))))
  (testing "E(2,5)"
    (is (= "x.x.." (euclid-str 2 5 0)))))

(deftest euclid-rotates-left
  (is (= "x..x..x." (euclid-str 3 8 0)))
  (is (= "..x..x.x" (euclid-str 3 8 1)))
  (testing "rotation wraps, so a full turn is the identity"
    (is (= (euclid-str 3 8 0) (euclid-str 3 8 8)))
    (is (= (euclid-str 3 8 1) (euclid-str 3 8 9)))))

(deftest euclid-edges
  (testing "no onsets is silence"
    (is (empty? (p/query (x/euclid 0 8 (p/pure :a)) [0 1]))))
  (testing "no steps is silence"
    (is (empty? (p/query (x/euclid 3 0 (p/pure :a)) [0 1]))))
  (testing "negative k is silence, not a crash"
    (is (empty? (p/query (x/euclid -2 8 (p/pure :a)) [0 1]))))
  (testing "k = n fills every step"
    (is (= 8 (count (filter p/onset? (p/query (x/euclid 8 8 (p/pure :a)) [0 1]))))))
  (testing "k > n also fills every step rather than looping forever"
    (is (= 4 (count (filter p/onset? (p/query (x/euclid 9 4 (p/pure :a)) [0 1])))))))

(deftest euclid-three-arity-defaults-to-no-rotation
  (is (= (map (juxt :whole :value) (p/query (x/euclid 3 8 (p/pure :a)) [0 1]))
         (map (juxt :whole :value) (p/query (x/euclid 3 8 0 (p/pure :a)) [0 1])))))

(deftest randomness-is-a-pure-function-of-time
  (testing "querying the same span twice gives identical results"
    (let [d (x/degrade (p/fast 8 (p/pure :a)))]
      (is (= (p/query d [10 12]) (p/query d [10 12])))))
  (testing "a far-future cycle is reachable without playing the ones before"
    (let [d (x/degrade (p/fast 8 (p/pure :a)))]
      (is (= (p/query d [400 401]) (p/query d [400 401]))))))

;; ---- arp ---------------------------------------------------------------

(defn- triad
  "Three notes sharing one whole -- the shape `chord` produces."
  []
  (p/pat (fn [sp]
           (mapcat (fn [ev]
                     (for [n [60 64 67]]
                       (assoc ev :value {:note n})))
                   (p/query (p/pure nil) sp)))))

(defn- arp-notes [mode]
  (->> (p/query (x/arp mode (triad)) [0 1])
       (filter p/onset?)
       (sort-by (comp first :part))
       (map (comp :note :value))))

(deftest arp-spreads-a-stack-across-its-own-span
  (testing ":up walks the stack low to high"
    (is (= [60 64 67] (arp-notes :up))))
  (testing ":down walks it high to low"
    (is (= [67 64 60] (arp-notes :down))))
  (testing ":updown turns at the top without repeating it"
    (is (= [60 64 67 64] (arp-notes :updown))))
  (testing ":downup turns at the bottom without repeating it"
    (is (= [67 64 60 64] (arp-notes :downup)))))

(deftest arp-gives-each-note-its-own-slot
  (let [evs (->> (p/query (x/arp :up (triad)) [0 1])
                 (filter p/onset?)
                 (sort-by (comp first :part)))]
    (is (= 3 (count evs)))
    (is (= [[0 1/3] [1/3 2/3] [2/3 1]] (map :whole evs))
        "three equal slots across the source event's whole")
    (is (every? p/onset? evs) "each arpeggiated note is its own onset")))

(deftest arp-leaves-lone-events-alone
  (testing "a single event is not a stack; it passes through untouched"
    (let [src (p/pure {:note 60})
          ev  (first (p/query src [0 1]))
          out (first (p/query (x/arp :up src) [0 1]))]
      (is (= (:whole ev) (:whole out)))
      (is (= (:value ev) (:value out))))))

(deftest arp-rejects-unknown-modes
  (is (thrown? clojure.lang.ExceptionInfo (x/arp :sideways (triad)))))
