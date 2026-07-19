(ns watchlist.match
  "Name-matching for sanctions/PEP screening — pure functions, no I/O.

   HONESTY NOTE (do not remove): matching a query name against a watchlist
   entity by string similarity is a real, hard problem commercial vendors
   (ComplyAdvantage, Refinitiv World-Check, Dow Jones) spend significant
   ongoing effort tuning. A naive exact-string match has a very high
   false-negative rate (misses real matches from transliteration variants,
   e.g. Arabic/Cyrillic romanization drift, that never look like an exact
   or even Jaro-Winkler-close string). This ns's tiered scoring is a real,
   deterministic, tested algorithm — not a black box — but its bounds are
   explicit and narrower than a production compliance vendor's: anything
   below `low-threshold` is NOT surfaced as a candidate at all. Treat that
   as a documented false-negative risk (README.md), not an implementation
   detail to hide."
  (:require [clojure.string :as str]))

(defn normalize
  "Lowercase, strip anything outside [a-z0-9 ], collapse whitespace, trim.
   Diacritic-stripping is NOT attempted here (portable .cljc has no
   built-in Unicode normalizer without a dependency) -- a name with
   diacritics that isn't ALSO listed in its plain-ASCII form on the source
   list is a real, documented gap (README.md), not silently handled."
  [s]
  (-> (str s)
      (str/lower-case)
      (str/replace #"[^a-z0-9 ]" " ")
      (str/replace #"\s+" " ")
      str/trim))

(defn tokens [s] (vec (remove str/blank? (str/split (normalize s) #" "))))

;; --- Jaro-Winkler --------------------------------------------------------
;; Standard algorithm (Winkler 1990), verified against the reference
;; vectors from Winkler's own published examples (see match_test.cljc):
;; jaro-winkler("MARTHA","MARHTA") = 0.9611, ("DWAYNE","DUANE") = 0.8400,
;; ("DIXON","DICKSONX") = 0.8133.

(defn- jaro [s1 s2]
  (let [s1 (vec s1) s2 (vec s2)
        len1 (count s1) len2 (count s2)]
    (cond
      (and (zero? len1) (zero? len2)) 1.0
      (or (zero? len1) (zero? len2)) 0.0
      :else
      (let [match-distance (max 0 (dec (quot (max len1 len2) 2)))
            ;; plain persistent vectors of booleans in atoms, not
            ;; boolean-array/aset -- keeps this fully portable .cljc without
            ;; relying on primitive-array mutation semantics matching
            ;; exactly between JVM and cljs.
            s1-matched (atom (vec (repeat len1 false)))
            s2-matched (atom (vec (repeat len2 false)))
            matches (atom 0)]
        (dotimes [i len1]
          (let [lo (max 0 (- i match-distance))
                hi (min (dec len2) (+ i match-distance))]
            (loop [j lo]
              (when (<= j hi)
                (if (and (not (nth @s2-matched j)) (= (s1 i) (s2 j)))
                  (do (swap! s1-matched assoc i true)
                      (swap! s2-matched assoc j true)
                      (swap! matches inc))
                  (recur (inc j)))))))
        (if (zero? @matches)
          0.0
          (let [m (double @matches)
                s1-matched-chars (keep-indexed (fn [i v] (when v (s1 i))) @s1-matched)
                s2-matched-chars (keep-indexed (fn [i v] (when v (s2 i))) @s2-matched)
                transpositions (/ (count (remove true? (map = s1-matched-chars s2-matched-chars))) 2.0)]
            (/ (+ (/ m len1) (/ m len2) (/ (- m transpositions) m)) 3.0)))))))

(defn jaro-winkler
  "Jaro-Winkler similarity in [0.0 1.0]. `prefix-weight` defaults to the
   standard 0.1; `max-prefix` (common-prefix bonus cap) defaults to the
   standard 4."
  ([s1 s2] (jaro-winkler s1 s2 0.1 4))
  ([s1 s2 prefix-weight max-prefix]
   (let [j (jaro s1 s2)
         prefix (count (take-while true? (map = s1 s2)))
         l (min prefix max-prefix)]
     (+ j (* l prefix-weight (- 1.0 j))))))

;; --- tiered candidate scoring ---------------------------------------------

(def high-threshold 0.92)
(def low-threshold 0.80)

(defn- token-set-subset?
  "Every token in `query-tokens` appears somewhere in `candidate-tokens`
   (order-independent) -- catches reordered names (\"Doe John\" vs
   \"John Doe\") a pure Jaro-Winkler full-string comparison would miss."
  [query-tokens candidate-tokens]
  (let [candidate-set (set candidate-tokens)]
    (and (seq query-tokens) (every? candidate-set query-tokens))))

(defn- token-overlap-ratio [query-tokens candidate-tokens]
  (if (empty? query-tokens)
    0.0
    (/ (count (filter (set candidate-tokens) query-tokens)) (double (count query-tokens)))))

(defn score-name
  "Compare normalized `query` against normalized `candidate`. Returns
   {:tier :exact|:fuzzy-high|:fuzzy-low|nil :confidence double
    :jaro-winkler double :token-overlap double}. `:tier` nil means below
   `low-threshold` on every signal -- NOT a candidate (see this ns's
   module doc)."
  [query candidate]
  (let [nq (normalize query) nc (normalize candidate)
        qt (tokens query) ct (tokens candidate)
        jw (jaro-winkler nq nc)
        overlap (token-overlap-ratio qt ct)
        subset? (token-set-subset? qt ct)]
    (cond
      (and (seq nq) (= nq nc))
      {:tier :exact :confidence 1.0 :jaro-winkler jw :token-overlap overlap}

      (or (>= jw high-threshold) subset?)
      {:tier :fuzzy-high
       :confidence (max 0.75 (min 0.92 jw))
       :jaro-winkler jw :token-overlap overlap}

      (or (>= jw low-threshold) (>= overlap 0.6))
      {:tier :fuzzy-low
       :confidence (max 0.55 (min 0.75 jw))
       :jaro-winkler jw :token-overlap overlap}

      :else
      {:tier nil :confidence 0.0 :jaro-winkler jw :token-overlap overlap})))

(defn candidate-names
  "All names a query could match against for one entity: primary name plus
   every alias, each tagged so a hit can say WHICH name matched."
  [entity]
  (into [{:name (:entity/primary-name entity) :kind :primary}]
        (map (fn [a] {:name a :kind :alias}))
        (:entity/aliases entity)))

(defn best-entity-match
  "The single best-scoring name (primary or alias) for `entity` against
   `query`, or nil if every name scored below `low-threshold`. Returns the
   score map plus :matched-name/:matched-name-kind."
  [query entity]
  (let [scored (for [{:keys [name kind]} (candidate-names entity)
                      :when (seq name)
                      :let [s (score-name query name)]
                      :when (:tier s)]
                  (assoc s :matched-name name :matched-name-kind kind))]
    (when (seq scored)
      (apply max-key :confidence scored))))
