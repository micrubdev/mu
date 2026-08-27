(ns mu.grammar-test
  (:require [clojure.test :refer [deftest is testing]]
            [mu.grammar :as g]
            [mu.pattern :as p]))

(defn- vals-at
  "Values of the onsets in one cycle, in time order."
  [q cyc]
  (->> (p/query q [cyc (inc cyc)])
       (filter p/onset?)
       (sort-by (comp first :part))
       (map :value)))

;; ---- lsys --------------------------------------------------------------

(def ^:private fib-rules
  "The Fibonacci word: 0 -> 01, 1 -> 0. Generation lengths are the
  Fibonacci numbers, which is what makes it a good growth-guard test."
  {0 [0 1], 1 [0]})

(defn- lsys-notes [rules axiom n]
  (map :note (vals-at (g/lsys rules axiom n) 0)))

(deftest lsys-expands-the-fibonacci-word
  (is (= [0]                         (lsys-notes fib-rules [0] 0)) "generation 0 is the axiom")
  (is (= [0 1]                       (lsys-notes fib-rules [0] 1)))
  (is (= [0 1 0]                     (lsys-notes fib-rules [0] 2)))
  (is (= [0 1 0 0 1]                 (lsys-notes fib-rules [0] 3)))
  (is (= [0 1 0 0 1 0 1 0]           (lsys-notes fib-rules [0] 4)))
  (is (= [0 1 0 0 1 0 1 0 0 1 0 0 1] (lsys-notes fib-rules [0] 5)))
  (testing "lengths are the Fibonacci numbers"
    (is (= [1 2 3 5 8 13]
           (map #(count (lsys-notes fib-rules [0] %)) (range 6))))))

(deftest lsys-handles-a-keyword-alphabet
  (testing "Lindenmayer's algae: a -> ab, b -> a"
    (let [algae {:a [:a :b], :b [:a]}]
      (is (= [:a :b]          (vals-at (g/lsys algae [:a] 1) 0)))
      (is (= [:a :b :a]       (vals-at (g/lsys algae [:a] 2) 0)))
      (is (= [:a :b :a :a :b] (vals-at (g/lsys algae [:a] 3) 0))))))

(deftest lsys-leaves-symbols-with-no-rule-alone
  (testing "a symbol with no rule is a constant, rewriting to itself"
    (let [koch {:f [:f :+ :f :- :f :- :f :+ :f]}]
      (is (= [:f :+ :f :- :f :- :f :+ :f] (vals-at (g/lsys koch [:f] 1) 0)))
      (testing "and survives further generations"
        ;; five :f each expand to 9, the four constants stay: 5*9 + 4
        (is (= 49 (count (vals-at (g/lsys koch [:f] 2) 0))))))))

(deftest lsys-maps-numbers-to-notes-and-leaves-the-rest-raw
  (is (= [{:note 0} {:note 1}] (vals-at (g/lsys fib-rules [0] 1) 0)))
  (is (= [:a] (vals-at (g/lsys {} [:a] 3) 0))))

(deftest lsys-refuses-to-expand-past-the-cap
  (testing "generation 16 is 2584 symbols and still allowed"
    (is (= 2584 (count (vals-at (g/lsys fib-rules [0] 16) 0)))))
  (testing "generation 17 would be 4181, over the 4096 cap, and throws"
    (is (thrown? clojure.lang.ExceptionInfo (g/lsys fib-rules [0] 17)))))

(deftest lsys-edges
  (testing "an empty axiom is silence"
    (is (empty? (p/query (g/lsys fib-rules [] 5) [0 1]))))
  (testing "n <= 0 is the axiom itself, not a crash"
    (is (= [0] (lsys-notes fib-rules [0] 0)))
    (is (= [0] (lsys-notes fib-rules [0] -3))))
  (testing "everything lands in one cycle, like sub and notes"
    (is (= [[0 1/3] [1/3 2/3] [2/3 1]]
           (->> (p/query (g/lsys fib-rules [0] 2) [0 1])
                (filter p/onset?) (sort-by (comp first :part)) (map :whole))))))
