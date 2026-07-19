# toshokan (図書館)

Read-only reference/archive actor — harvests public bibliographic metadata
from national library catalogs and preserves it, per the pattern in
[ADR-2607072300](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2607072300-actor-public-data-git-journal-kotobase-index.edn)
(this repo's own git history is the source of truth; kotobase.net is a
derived, rebuildable index over it) and
[ADR-2607113500](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2607113500-kotobase-net-ingestion-cloud-itonami-lei.edn)
(the pull-based batch fold job shape). Full design rationale, the 5-source
research, and the real end-to-end verification numbers are in
[ADR-2607199900](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2607199900-toshokan-national-library-catalog-ingestion.edn).

This is a metadata-only project: title / creator / publisher / date /
identifiers / subject. It never harvests full text or digitized page
images, and never bypasses paywalls or access controls.

## Sources

| source | status | endpoint | auth |
|---|---|---|---|
| `ndl` — National Diet Library (Japan) | implemented | `ndlsearch.ndl.go.jp/api/sru` (SRU, dcndl schema) | none |
| `loc` — Library of Congress (US) | implemented | `www.loc.gov/search/` (JSON API) | none |
| `korea-nl` — National Library of Korea | implemented, **blocked on a key** | `www.nl.go.kr/NL/search/openApi/search.do` | `NL_GO_KR_API_KEY` — register at nl.go.kr (manual, per-account approval; unverified whether non-Korean applicants can register) |
| `iran-nlai` — National Library of Iran | **`:spec`, not implemented** | none exists | — |
| `russia-rsl` — Russian State Library / National Library of Russia | **`:spec`, not implemented** | none exists | — |

Iran and Russia have no official API, OAI-PMH, SRU, or bulk export — only
unofficial third-party HTML scrapers exist for either, and both sit in
sanctions-sensitive territory that hasn't had legal review for this use.
See `src/toshokan/sources/iran_nlai.cljs` and `russia_rsl.cljs` for the
full reasoning; calling `search` on either throws immediately rather than
silently no-op'ing.

## Usage

All scripts run under [nbb](https://github.com/babashka/nbb) per this
workspace's runtime priority rules (kotoba wasm → clojurewasm → cljs →
nbb). From this repo's root:

```bash
# harvest a source into this repo's own 80-data/public/<source>.journal.edn
npx nbb --classpath "src" scripts/harvest.cljs ndl 'title="夏目漱石"' 20
npx nbb --classpath "src" scripts/harvest.cljs loc "natsume soseki" 20
NL_GO_KR_API_KEY=... npx nbb --classpath "src" scripts/harvest.cljs korea-nl "소세키" 20

# fold every local journal into kotobase.net (self-mints an Ed25519
# identity into scripts/.kotobase-ingest-toshokan-identity.hex on first
# run -- gitignored, back it up, losing it orphans the graph)
NODE_PATH="<path-to>/kotoba-lang/kotobase-client/node_modules" \
  npx nbb --classpath "<path-to>/kotoba-lang/kotobase-client/src:src" \
  scripts/kotobase-ingest-toshokan.cljs

# tests (fixture-based, no live network)
npx nbb --classpath "src:test" scripts/run-tests.cljs
```

## Schema

Quads use the `[entity attr value tx op]` shape from ADR-2607072300.
Entities are namespaced by source: `ndl:<bib-id>`, `loc:<lccn-or-id>`,
`korea-nl:<control-no>`. Attributes: `:library/source`,
`:library/source-url`, `:library/title`, `:library/creator` (many),
`:library/publisher`, `:library/date`, `:library/language`,
`:library/format`, `:library/ndc` / `:library/lccn` / `:library/isbn`,
`:library/subject` (many), `:library/retrieved-at`. See
`src/toshokan/quad.cljs` and each `toshokan.sources.*` namespace.

## Scope not yet covered

Bulk/continuous harvesting (NDL's Free Data Service + weekly JAPAN/MARC
dumps, LOC's 2017 25M-record bulk set, OAI-PMH resumption-token paging),
scheduling, and Korea NL's key registration are all deliberately out of
scope for this first cut — see ADR-2607199900's Consequences.
