# watchlist-screen

Sanctions/PEP watchlist screening for kotoba-lang. Ingests real, public,
freely-licensed government sanctions data (OFAC SDN List, UN Security
Council Consolidated List) into normalized EDN, and screens a name against
it with a tiered, confidence-scored, staleness-aware match.

This is genuinely real: `resources/watchlist/lists/` in this repo ships an
actual snapshot fetched from the live sources (not synthetic data), and
`scripts/refresh_lists.cljs` re-fetches it. The match algorithm, staleness
tracking, and `aml.ports/IAmlScreening` integration are all real, tested
code — but read "Honesty boundary" below before relying on this for
anything with real compliance weight.

## Usage

```clojure
(require '[watchlist.adapters.edn-index :as idx]
         '[watchlist.core :as core])

(def index (idx/edn-index "resources/watchlist/lists"))
(core/screen index "some name to check" (System/currentTimeMillis))
;;=> {:watchlist/candidates [{:tier :fuzzy-high :confidence 0.83 ...}]
;;    :watchlist/stale? false
;;    :watchlist/list-age-days 2.1
;;    :watchlist/checked-sources [:ofac-sdn :un-consolidated]
;;    :watchlist/checked-at 1753000000000}
```

Refreshing the data:

```bash
nbb -cp src scripts/refresh_lists.cljs --out resources/watchlist/lists
```

## Sources

- **OFAC SDN List** — `https://www.treasury.gov/ofac/downloads/sdn.xml`,
  freely downloadable, no authentication. ~19,000 entities as of this
  repo's initial snapshot.
- **UN Security Council Consolidated List** —
  `https://scsanctions.un.org/resources/xml/en/consolidated.xml`, freely
  downloadable, no authentication. ~1,000 entities as of this repo's
  initial snapshot.
- **EU Consolidated Financial Sanctions List** — **not implemented**. The
  EU's machine-readable source (the FSD/FSF API) requires a registered
  access token; every unauthenticated fetch attempt during this repo's
  development returned HTTP 500. See
  `src/watchlist/adapters/eu_consolidated.cljc`'s module doc. Provisioning
  a token is an owner action, not something this repo scripts around.

## Honesty boundary (read before relying on this for anything real)

- **Data ingestion**: real. Both parsers were built and tested against the
  actual live XML files, not reconstructed from memory or a stale spec —
  every namespace's doc comment says so and names the exact URL.
- **Name matching**: real, deterministic, tested (including Jaro-Winkler
  verified against Winkler's own published reference vectors) — but a
  known, documented false-negative risk: anything scoring below 0.80 on
  every signal is not surfaced as a candidate at all, and diacritics are
  stripped rather than transliterated (`watchlist.match`'s module doc).
  This is narrower coverage than a commercial compliance vendor
  (ComplyAdvantage, Refinitiv World-Check, Dow Jones) that tunes match
  quality as an ongoing operational job.
- **Staleness**: every screening result carries `:watchlist/stale?` and
  `:watchlist/list-age-days` — a caller cannot mistake a result checked
  against 6-month-old data for one checked against current data, and an
  index that never loaded at all fails closed (`:watchlist/stale? true`),
  never silently looking like a clean check.
- **`:non-adjudicating` always** — mirrors `ekyc`/`aml`/`identity`'s
  existing convention. A `:deny`/`:challenge`/`:review` level from
  `watchlist.adapters.aml-port` is evidence for a human/compliance-officer
  decision, never an automated final adjudication.
- **Not yet wired into `aml.core/screen`'s routing** —
  `aml.model/routes` is `[:yabai :malak]`, a closed vector this repo does
  not own. `watchlist.adapters.aml-port/screening-port` is directly
  callable standalone (`(screen! port request :watchlist)`); adding
  `:watchlist` as a real third route requires a small follow-up PR to
  `kotoba-lang/aml` itself.
- **Performance**: the bundled XML parser (`watchlist.adapters.xml`, a
  hand-rolled zero-dependency reader shared by the org's other XML-
  consuming libraries) is a straightforward char-scanning implementation —
  parsing the full ~19,000-entity OFAC file takes on the order of tens of
  seconds on the JVM, longer under nbb's interpreter. Fine for a periodic
  batch refresh job; not tuned for a hot path.

See `MATURITY.md` and `90-docs/adr/*-kotoba-lang-watchlist-screen.edn` (in
the `com-junkawasaki/root` superproject) for the full design rationale.
