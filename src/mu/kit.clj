(ns mu.kit
  "Drum names and the kits that realise them.

  Percussion note numbers are a lookup table, not a scale: 36 is a kick
  because General MIDI says so, and on a sampler it is whatever the pad
  happens to be mapped to. Transposing a drum part up a fifth is
  meaningless. So a drum event carries :drum -- WHICH SOUND is meant --
  and a kit says which number realises it. Same split as :note/:spell.

  Purity boundary: as with mu.pattern, nothing here reads wall-clock
  time, touches MIDI, or holds mutable state."
  (:require [mu.pattern :as p]))

(def gm
  "General MIDI percussion: notes 35-81 under descriptive names, plus
  the short aliases performers actually type. An alias is an ordinary
  extra key pointing at the same number, not a second lookup layer."
  {;; kicks, snares, claps
   :acoustic-bass-drum 35  :bd2 35
   :bass-drum          36  :bd  36
   :side-stick         37  :rim 37
   :acoustic-snare     38  :sn  38
   :hand-clap          39  :cp  39
   :electric-snare     40  :sn2 40
   ;; toms and hats
   :low-floor-tom      41  :lt  41
   :closed-hihat       42  :hh  42
   :high-floor-tom     43  :ft  43
   :pedal-hihat        44  :ph  44
   :low-tom            45
   :open-hihat         46  :oh  46
   :low-mid-tom        47  :mt  47
   :hi-mid-tom         48  :ht  48
   :crash              49  :cr  49
   :high-tom           50
   ;; cymbals and metal
   :ride               51  :rd  51
   :chinese-cymbal     52
   :ride-bell          53
   :tambourine         54  :tb  54
   :splash             55
   :cowbell            56  :cb  56
   :crash-2            57
   :vibraslap          58
   :ride-2             59
   ;; hand percussion
   :hi-bongo           60
   :low-bongo          61
   :mute-hi-conga      62
   :open-hi-conga      63
   :low-conga          64
   :high-timbale       65
   :low-timbale        66
   :high-agogo         67
   :low-agogo          68
   ;; shakers, whistles, blocks
   :cabasa             69
   :maracas            70
   :short-whistle      71
   :long-whistle       72
   :short-guiro        73
   :long-guiro         74
   :claves             75
   :hi-wood-block      76
   :low-wood-block     77
   :mute-cuica         78
   :open-cuica         79
   :mute-triangle      80
   :open-triangle      81})

(defn- normalize
  "A kit entry as an event fragment. A bare number is shorthand for a
  note; a map is merged as-is, so a kit can set anything an event holds."
  [entry]
  (if (number? entry) {:note entry} entry))

(defn resolve-value
  "Resolve one event value against kit `k`.

  Left alone when the value is not a map, carries no :drum, or already
  has a :note. That last guard is what makes `kit` idempotent and lets
  an inner kit win over an outer one -- including for a name only the
  inner kit knows, which would otherwise throw on the outer pass.

  :chan 9 is the default rather than a field in every kit entry: a drum
  name MEANS percussion, and channel 10 is what percussion means in
  MIDI. A sampler kit that wants otherwise sets :chan per entry.
  Precedence runs channel default < kit entry < the event's own keys."
  [k v]
  (if-not (and (map? v) (:drum v) (not (:note v)))
    v
    (let [d (:drum v)]
      (if-let [entry (get k d)]
        (merge {:chan 9} (normalize entry) v)
        (throw (ex-info (str "unknown drum name: " d)
                        {:drum d :known (sort (keys k))}))))))

(defn kit
  "Resolve this pattern's drum names against kit `k`.

  A no-op on pitched patterns, which is why the player can apply it to
  every voice unconditionally. Depends only on each event's own value,
  never on which events co-occur in the queried span, so unlike `rev`
  or `every` it needs no cycle anchoring."
  [k pat]
  (p/fmap #(resolve-value k %) pat))
