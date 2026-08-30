(ns mu.kit-test
  (:require [clojure.test :refer [deftest is testing]]
            [mu.kit :as k]
            [mu.pattern :as p]))

(defn- values
  "The event values of one cycle, in onset order."
  [pat]
  (->> (p/query pat [0 1])
       (filter p/onset?)
       (sort-by (comp first :whole))
       (map :value)))

(deftest number-entry-resolves-to-note-on-channel-nine
  (is (= [{:drum :bd :note 36 :chan 9}]
         (values (k/kit {:bd 36} (p/pure {:drum :bd}))))))

(deftest map-entry-merges-whole
  (is (= [{:drum :rim :note 37 :chan 9 :vel 0.5}]
         (values (k/kit {:rim {:note 37 :vel 0.5}} (p/pure {:drum :rim}))))))

(deftest the-events-own-keys-beat-the-kits
  (testing "an fmap'd :vel survives a kit that ghosts that drum"
    (is (= [{:drum :rim :note 37 :chan 9 :vel 0.9}]
           (values (k/kit {:rim {:note 37 :vel 0.5}}
                          (p/pure {:drum :rim :vel 0.9})))))))

(deftest a-per-entry-chan-beats-the-default
  (is (= [{:drum :bd :note 60 :chan 3}]
         (values (k/kit {:bd {:note 60 :chan 3}} (p/pure {:drum :bd}))))))

(deftest a-pitched-pattern-is-untouched
  (let [pitched (p/pure {:note 60 :spell {:step :c :alter 0 :octave 4}})]
    (is (= (values pitched) (values (k/kit k/gm pitched))))))

(deftest a-non-map-value-is-untouched
  (testing "signals and lifted scalars flow through unchanged"
    (is (= [:a] (values (k/kit k/gm (p/pure :a)))))
    (is (= [0.5] (values (k/kit k/gm (p/pure 0.5)))))))

(deftest an-event-that-already-has-a-note-is-untouched
  (testing "idempotence: applying kit twice is applying it once"
    (let [once (k/kit k/gm (p/pure {:drum :bd}))]
      (is (= (values once) (values (k/kit k/gm once))))))
  (testing "an inner kit wins over an outer one"
    (is (= [{:drum :bd :note 99 :chan 9}]
           (values (k/kit k/gm (k/kit {:bd 99} (p/pure {:drum :bd})))))))
  (testing "an inner kit's private name does not throw on the outer pass"
    (is (= [{:drum :tabla-na :note 60 :chan 9}]
           (values (k/kit k/gm (k/kit {:tabla-na 60}
                                      (p/pure {:drum :tabla-na}))))))))

(deftest an-unknown-drum-name-throws
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown drum name"
        (values (k/kit k/gm (p/pure {:drum :bdd}))))))

(deftest gm-covers-every-general-midi-percussion-note
  (is (= (set (range 35 82)) (set (vals k/gm)))))

(deftest every-alias-agrees-with-its-canonical-name
  (doseq [[alias canonical]
          {:bd2 :acoustic-bass-drum, :bd  :bass-drum
           :rim :side-stick,         :sn  :acoustic-snare
           :cp  :hand-clap,          :sn2 :electric-snare
           :lt  :low-floor-tom,      :hh  :closed-hihat
           :ft  :high-floor-tom,     :ph  :pedal-hihat
           :oh  :open-hihat,         :mt  :low-mid-tom
           :ht  :hi-mid-tom,         :cr  :crash
           :rd  :ride,               :tb  :tambourine
           :cb  :cowbell}]
    (is (= (k/gm canonical) (k/gm alias))
        (str alias " should agree with " canonical))))
