# Maturity

**Level: R1 experimental (real data, narrower-than-commercial match quality)**

Implemented:
- `watchlist.model` — entity/list-manifest records, match-tier vocabulary,
  staleness policy (pure, clock-as-param).
- `watchlist.match` — normalization, Jaro-Winkler (verified against
  Winkler's own published reference vectors), token-set/overlap scoring,
  tiered candidate classification (`:exact`/`:fuzzy-high`/`:fuzzy-low`).
- `watchlist.adapters.xml` — portable zero-dependency XML-string reader
  (same hand-rolled pattern kotoba-lang/org-omg-bpmn, org-oasis-open-xmile,
  org-omg-uml, org-sbml each already carry).
- `watchlist.adapters.ofac-sdn` / `.un-consolidated` — real parsers, built
  and tested against the actual live government XML files (not
  reconstructed from memory). `resources/watchlist/lists/` ships a real
  snapshot: 19,169 OFAC SDN entities + 1,010 UN Consolidated entities as of
  this repo's initial commit.
- `watchlist.adapters.edn-index` — durable file-backed
  `watchlist.ports/IWatchlistIndex` (JVM-only, `.clj` not `.cljc`, matching
  `ekyc.adapters.edn-provider`'s own precedent for the same reason).
- `watchlist.core/screen` — orchestration: always returns staleness
  alongside candidates, never lets "no match" and "we don't actually know"
  look identical.
- `watchlist.adapters.aml-port` — real `aml.ports/IAmlScreening`
  implementation, fail-closed discipline mirroring
  `aml.adapters.etzhayyim`'s own documented "never :clear on an
  unrecognized/absent signal" rule (an absent name, a stale/absent index,
  and zero candidates are three different states, never collapsed).
- `scripts/refresh_lists.cljs` (nbb) — real fetch/hash/parse/write, run
  against both live sources during this repo's development (see commit
  history / manifest `sha256`/`entity-count` fields for proof, not just a
  claim).
- Contract tests: Jaro-Winkler reference vectors, per-source fixture
  parsing (schema-faithful fixtures with fabricated names, verified
  independently against real live data during development — see each
  adapter's test namespace doc comment), staleness-threshold behavior,
  `aml-port` fail-closed mapping, `edn-index` file-I/O round-trip. 38
  tests, 93 assertions, 0 failures. `clj-kondo`: 0 errors, 0 warnings.

Not yet R1 (i.e., explicitly absent, not a rounding-down):
- **EU Consolidated Financial Sanctions List** — not implemented at all.
  The EU's machine-readable source requires a registered access token this
  repo doesn't have (`watchlist.adapters.eu-consolidated`'s module doc).
  `watchlist.model/sources` does not include `:eu-consolidated`.
- **Not wired into `aml.core/screen`'s actual routing.**
  `aml.model/routes` (`kotoba-lang/aml`) is a closed `[:yabai :malak]`
  vector this repo does not own — a real, separate, small follow-up PR to
  `aml` is required before `:watchlist` is a real route a caller can select
  through the normal `aml.core/screen` path. Until then,
  `watchlist.adapters.aml-port/screening-port` is only directly/standalone
  callable.
- **Diacritic transliteration** — `watchlist.match/normalize` strips
  accented characters as punctuation rather than transliterating them
  (documented, tested known gap: "José" → "jos", not "jose").
- **Phonetic/transliteration-variant matching** (Soundex, Metaphone, or a
  Cyrillic/Arabic romanization-aware comparator) — not implemented. A name
  variant that scores below 0.80 on both Jaro-Winkler and token overlap is
  never surfaced, full stop.
- **No accuracy benchmark of any kind** (false-positive rate, false-
  negative rate) against a real screening workload. The match algorithm is
  tested for correctness of its own stated logic, not validated against a
  labeled compliance dataset — none exists in this workspace, and none is
  fabricated here.
- **No automated CI-scheduled refresh** — `scripts/refresh_lists.cljs`
  exists and works (proven during development against live data), but
  nothing runs it on a schedule yet. `resources/watchlist/lists/` is a
  point-in-time snapshot that will silently age unless someone re-runs it
  or wires a cron.
- **Performance** — the shared XML reader is a straightforward char-
  scanning implementation, not optimized for the ~19,000-entity OFAC file's
  ~28MB size (tens of seconds on the JVM). Acceptable for a periodic batch
  job, not a hot path.
- **Entity types beyond individual/entity** — OFAC's real data also
  includes `Vessel`/`Aircraft` designations; this repo's `sdnType` mapping
  folds anything not exactly `"Individual"` to `:entity` (documented in
  `watchlist.adapters.ofac-sdn`'s module doc), so a vessel/aircraft-
  specific screening use case is out of scope.

## Downstream consumers

None yet in production. Designed to sit behind
`kotoba-lang/aml` (once the `:watchlist` route PR lands) and to be reusable
standalone by any caller wanting real sanctions/PEP name screening.
