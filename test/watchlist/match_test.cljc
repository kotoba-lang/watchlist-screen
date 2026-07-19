(ns watchlist.match-test
  (:require [clojure.test :refer [deftest is]]
            [watchlist.match :as match]))

;; Reference vectors from Winkler's own published Jaro-Winkler examples,
;; reproduced identically across many independent implementations -- if
;; this fails, the algorithm itself has a bug, not a tuning issue.
(deftest jaro-winkler-reference-vectors
  (is (< (Math/abs (- 0.9611 (match/jaro-winkler "MARTHA" "MARHTA"))) 1e-3))
  (is (< (Math/abs (- 0.8400 (match/jaro-winkler "DWAYNE" "DUANE"))) 1e-3))
  (is (< (Math/abs (- 0.8133 (match/jaro-winkler "DIXON" "DICKSONX"))) 1e-3))
  (is (= 1.0 (match/jaro-winkler "SAME" "SAME")))
  (is (= 0.0 (match/jaro-winkler "" "ANYTHING")))
  (is (= 1.0 (match/jaro-winkler "" ""))))

(deftest normalize-strips-punctuation-and-collapses-whitespace
  (is (= "hello world" (match/normalize "  Hello,   WORLD!! ")))
  (is (= "" (match/normalize nil))))

(deftest normalize-does-not-transliterate-diacritics-known-gap
  ;; Documents the real, known limitation this ns's module doc warns about:
  ;; accented characters are stripped as punctuation, not transliterated to
  ;; their plain-ASCII equivalent -- "José" becomes "jos", not "jose". A
  ;; source-list entry only present in accented form would not match a
  ;; plain-ASCII query (or vice versa) under this normalizer.
  (is (= "jos mar a lvarez" (match/normalize "José-María  Álvarez."))))

(deftest tokens-splits-normalized-words
  (is (= ["john" "doe"] (match/tokens "  John   DOE! "))))

(deftest score-name-exact-tier
  (let [s (match/score-name "Ahmed Hassan" "AHMED HASSAN")]
    (is (= :exact (:tier s)))
    (is (= 1.0 (:confidence s)))))

(deftest score-name-fuzzy-high-tier-close-typo
  (let [s (match/score-name "Martha Stewart" "Marhta Stewart")]
    (is (= :fuzzy-high (:tier s)))
    (is (<= 0.75 (:confidence s) 0.92))))

(deftest score-name-fuzzy-high-tier-reordered-tokens
  (let [s (match/score-name "Stewart Martha" "Martha Stewart")]
    (is (= :fuzzy-high (:tier s)) "token-set-subset catches reordering a pure JW comparison would miss")))

(deftest score-name-fuzzy-low-tier-partial-overlap
  (let [s (match/score-name "John Michael Smith" "John Smith")]
    (is (contains? #{:fuzzy-high :fuzzy-low} (:tier s)))))

(deftest score-name-below-threshold-is-nil-tier-not-a-candidate
  (let [s (match/score-name "Alice Wonderland" "Bob Builder")]
    (is (nil? (:tier s)))))

(deftest best-entity-match-picks-highest-scoring-alias
  (let [entity {:entity/primary-name "Unrelated Person"
                :entity/aliases ["Some Other Name" "Ahmed Hassan Al-Zomor"]}
        m (match/best-entity-match "Ahmed Hasan Alzomor" entity)]
    (is (some? m))
    (is (= "Ahmed Hassan Al-Zomor" (:matched-name m)))
    (is (= :alias (:matched-name-kind m)))))

(deftest best-entity-match-nil-when-nothing-clears-the-low-threshold
  (let [entity {:entity/primary-name "Zzyzx Qwerty" :entity/aliases []}]
    (is (nil? (match/best-entity-match "Completely Different Name" entity)))))
