(ns mu.score
  "The vertical score: a plaintext file where columns are voices and rows
  are steps, after textbeat. Written in an editor, loaded into the
  registry; `:w` is the performance gesture.

    ; forge stomp
    %bpm 120  %key d aeolian  %grid 4
    %chan drums 9

    bass     drums    lead
    1        :bd      _
    _        :hh      5
    b3       :sn      b3!
    5        :hh      8>

  `%` lines are directives. The first other line is the header: track
  names, whose column positions carry every row below. A cell is one
  literal in `notes` or -- under `%key` -- `deg` notation, `_` or empty
  for a rest, `[a b]` to subdivide. `%grid` is rows per cycle. A blank
  line ends a section; `@name` labels the block after it, `@name x3`
  repeats it, and a bare `@name` with no block replays a section named
  earlier. Sections play in order and loop, as `song`.

  `parse` and `compile` are pure and take strings; `load!` and `watch!`
  touch the file system and the registry."
  (:refer-clojure :exclude [compile])
  (:require [clojure.string :as str]
            [mu.harmony :as h]
            [mu.notation :as n]
            [mu.pattern :as p]
            [mu.player :as player]
            [mu.transform :as x]))

;; ---- parse ---------------------------------------------------------------

(defn- strip-comment [line]
  (str/trimr (first (str/split line #";" 2))))

(defn- directive
  "One `%name args` clause into a [key value] pair, or a merge fn for
  the ones that accumulate."
  [clause]
  (let [[nm & args] (str/split (str/trim clause) #"\s+")]
    (case nm
      "bpm"  [:bpm (Long/parseLong (first args))]
      "grid" [:grid (Long/parseLong (first args))]
      "key"  [:key [(keyword (first args)) (keyword (second args))]]
      "chan" [:chan {(first args) (Long/parseLong (second args))}]
      (throw (ex-info (str "mu: unknown directive %" nm) {:directive nm})))))

(defn- add-directive [m [k v]]
  (if (= k :chan) (update m :chan merge v) (assoc m k v)))

(defn- column-starts
  "Where each header word begins."
  [header]
  (->> (re-seq #"\S+" header)
       (reduce (fn [[acc from] w]
                 (let [i (str/index-of header w from)]
                   [(conj acc [w i]) (+ i (count w))]))
               [[] 0])
       first))

(defn- split-row
  "A row's cells, one per column, by the nearest column start at or
  left of each token. Missing cells are nil."
  [cols line]
  (let [m (java.util.regex.Pattern/compile "\\[[^\\]]*\\]|\\S+")
        matcher (.matcher m line)
        tokens (loop [acc []]
                 (if (.find matcher)
                   (recur (conj acc [(.group matcher) (.start matcher)]))
                   acc))
        idx-of (fn [pos]
                 (or (last (keep-indexed (fn [i [_ start]] (when (<= start pos) i)) cols))
                     0))]
    (reduce (fn [row [tok pos]] (assoc row (idx-of pos) tok))
            (vec (repeat (count cols) nil))
            tokens)))

(defn- label-line
  "`@name` or `@name x3` into {:label :repeat}, or nil."
  [line]
  (when-let [[_ lbl reps] (re-matches #"@(\S+)(?:\s+x(\d+))?" (str/trim line))]
    {:label lbl :repeat (if reps (Long/parseLong reps) 1)}))

(defn parse
  "A score string into {:directives {...} :tracks [names] :sections
  [{:label :repeat :rows [[cell ...] ...]}]}. Cells are the raw tokens;
  `compile` reads them."
  [src]
  (let [lines (map strip-comment (str/split-lines src))
        {:keys [directives body]}
        (reduce (fn [{:keys [directives body]} line]
                  (if (str/starts-with? (str/triml line) "%")
                    {:directives (reduce add-directive directives
                                         (map directive (remove str/blank? (str/split line #"%"))))
                     :body body}
                    {:directives directives :body (conj body line)}))
                {:directives {:grid 4 :chan {}} :body []}
                lines)
        body   (drop-while str/blank? body)
        header (first body)
        cols   (column-starts (or header ""))
        ;; Blocks: runs of non-blank lines after the header.
        blocks (->> (rest body)
                    (partition-by str/blank?)
                    (remove #(str/blank? (first %))))
        ;; A block may open with a label, or be a bare label alone.
        sections
        (loop [blocks blocks, defined {}, out [], n 0]
          (if-let [[line & more] (first blocks)]
            (let [lbl  (label-line line)
                  rows (if lbl more (cons line more))
                  n    (inc n)]
              (if (and lbl (empty? rows))
                (let [sec (or (defined (:label lbl))
                              (throw (ex-info (str "mu: @" (:label lbl) " replays a section that was never written")
                                              {:label (:label lbl)})))]
                  (recur (rest blocks) defined
                         (conj out (assoc sec :repeat (:repeat lbl))) n))
                (let [sec {:label  (or (:label lbl) (str n))
                           :repeat (or (:repeat lbl) 1)
                           :rows   (mapv #(split-row cols %) rows)}]
                  (recur (rest blocks) (assoc defined (:label sec) sec)
                         (conj out sec) n))))
            out))]
    (when-not header
      (throw (ex-info "mu: score has no header line" {})))
    {:directives directives
     :tracks     (mapv first cols)
     :sections   sections}))

;; ---- compile -------------------------------------------------------------

(defn- read-cell
  "A cell token into a pattern. `deg?` chooses degree notation.

  Not the Clojure reader: a score cell is its own small grammar, so
  `8>` -- a degree with a suffix, which the reader would reject as a
  number -- is fine here."
  [deg? tok]
  (letfn [(word->pat [w]
            (cond
              (= w "_")
              p/silence

              (re-matches #"-?\d+" w)
              (let [n (Long/parseLong w)]
                (p/pure (if deg? {:note (n/degree->zero-based n) :deg true} {:note n})))

              (str/starts-with? w ":")
              (p/pure (n/literal (keyword (subs w 1))))

              :else
              (let [sym (symbol w)]
                (or (some-> (if deg? (n/degree-literal sym) (n/literal sym)) p/pure)
                    (throw (ex-info (str "mu: not a " (if deg? "degree" "note") " literal: " w)
                                    {:cell tok}))))))]
    (cond
      (nil? tok) p/silence
      (str/starts-with? tok "[")
      (apply p/sub (map word->pat (re-seq #"\S+" (subs tok 1 (dec (count tok))))))
      :else (word->pat tok))))

(defn- rows->pattern
  "One track's column of cells, `grid` rows per cycle, into a pattern
  that many cycles long. A short final cycle is padded with rests so
  the grid never drifts."
  [deg? grid cells]
  (let [cycles (partition grid grid (repeat nil) cells)]
    (apply p/slowcat
           (for [rows cycles]
             (apply p/sub (map #(read-cell deg? %) rows))))))

(defn compile
  "A parsed score into {track-name pattern}. Every track is `song` of
  the sections; a `%key` puts the whole thing through `key`."
  [{:keys [directives tracks sections]}]
  (let [{:keys [grid key]} directives]
    (into {}
          (for [[i name] (map-indexed vector tracks)]
            (let [secs (for [{:keys [label repeat rows]} sections]
                         (let [cells (map #(nth % i) rows)
                               len   (max 1 (long (Math/ceil (/ (count cells) grid))))]
                           [label (* repeat len)
                            (rows->pattern (boolean key) grid cells)]))
                  pat  (apply x/song secs)]
              [name (if key (h/key (first key) (second key) pat) pat)])))))

;; ---- load ----------------------------------------------------------------

(defonce ^:private !loaded (atom {}))

(defn load!
  "Read a score file, compile it and register one voice per track under
  the track's name. Loading again replaces the voices -- and drops any
  the new score no longer has -- landing on the next cycle like any
  redefinition. Sets the tempo when the score says one."
  [path]
  (let [parsed (parse (slurp path))
        pats   (compile parsed)
        chans  (get-in parsed [:directives :chan])
        keys*  (set (map keyword (keys pats)))]
    (doseq [k (get @!loaded path)
            :when (not (keys* k))]
      (player/stop-voice! k))
    (doseq [[name pat] pats]
      (player/play! (keyword name) pat {:chan (get chans name 0)}))
    (when-let [b (get-in parsed [:directives :bpm])]
      (player/bpm b))
    (swap! !loaded assoc path keys*)
    keys*))

(defonce ^:private !watchers (atom {}))

(defn watch!
  "Reload `path` whenever its modification time changes, polling once a
  second. A score that fails to parse is reported and the last good
  one keeps playing."
  [path]
  (let [f (java.io.File. ^String path)
        running? (atom true)]
    (load! path)
    (future
      (loop [seen (.lastModified f)]
        (when @running?
          (Thread/sleep 1000)
          (let [now (.lastModified f)]
            (when (not= now seen)
              (try (load! path)
                   (catch Throwable t
                     (println (str "mu: " path ": " (.getMessage t) " -- keeping the last good score")))))
            (recur now)))))
    (swap! !watchers assoc path running?)
    path))

(defn unwatch!
  [path]
  (when-let [r (get @!watchers path)] (reset! r false))
  (swap! !watchers dissoc path)
  path)
