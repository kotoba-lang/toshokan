#!/usr/bin/env nbb
;; toshokan harvest CLI -- fetches from one live library source and appends
;; the results to this repo's own `80-data/public/<source>.journal.edn`
;; (the git-authoritative EDN quad-log per ADR-2607072300). This script
;; only ever WRITES to this repo's working tree; it never talks to
;; kotobase.net -- that's scripts/kotobase-ingest-toshokan.cljs's job, kept
;; separate the same way the two concerns are separated for
;; cloud-itonami-lei-* (journal-in-repo vs. fold-into-kotobase).
;;
;; Usage (from the repo root):
;;   npx nbb --classpath "src" scripts/harvest.cljs <source> "<query>" [max-records]
;;
;; <source> is one of: ndl | loc | korea-nl | dnb | bnf
;; (iran-nlai / russia-rsl are intentionally unimplemented -- see their
;; namespace docstrings and the repo README/ADR.)

(ns harvest
  (:require ["node:path" :as path]
            [clojure.string :as str]
            [toshokan.quad :as quad]
            [toshokan.sources.ndl :as ndl]
            [toshokan.sources.loc :as loc]
            [toshokan.sources.korea-nl :as korea-nl]
            [toshokan.sources.dnb :as dnb]
            [toshokan.sources.bnf :as bnf]))

(def sources
  {"ndl" {:search (fn [q n] (ndl/search q :max-records n)) :->quads ndl/->quads}
   "loc" {:search (fn [q n] (loc/search q :count n)) :->quads loc/->quads}
   "korea-nl" {:search (fn [q n] (korea-nl/search q :count n)) :->quads korea-nl/->quads}
   "dnb" {:search (fn [q n] (dnb/search q :max-records n)) :->quads dnb/->quads}
   "bnf" {:search (fn [q n] (bnf/search q :max-records n)) :->quads bnf/->quads}})

(defn -main [source-name query max-records]
  (if-let [{:keys [search ->quads]} (get sources source-name)]
    (let [journal-path (path/join "80-data" "public" (str source-name ".journal.edn"))
          existing (quad/read-journal journal-path)
          tx (quad/next-tx existing)
          retrieved-at (.toISOString (js/Date.))]
      (-> (search query (or max-records 20))
          (.then (fn [recs]
                   (println "fetched" (count recs) "records from" source-name "for query" (pr-str query))
                   (let [new-quads (into [] (mapcat #(->quads tx retrieved-at %)) recs)]
                     (quad/append-journal! journal-path new-quads)
                     (println "wrote" (count new-quads) "quads (tx" tx ") to" journal-path))))
          (.catch (fn [e]
                    (println "FAILED:" (.-message e))
                    (js/process.exit 1)))))
    (do
      (println "Unknown source" (pr-str source-name) "-- choose one of" (pr-str (keys sources)))
      (js/process.exit 1))))

;; argv shape varies with how nbb was invoked (`--classpath` shifts the
;; script-path index), so locate this script's own path in argv rather than
;; assuming a fixed offset, and take everything after it as user args.
(let [argv (js->clj js/process.argv)
      script-idx (or (some (fn [[i a]] (when (str/ends-with? a "harvest.cljs") i))
                            (map-indexed vector argv))
                     2)
      [source-name query max-str] (drop (inc script-idx) argv)]
  (if (and source-name query)
    (-main source-name query (some-> max-str js/parseInt))
    (do
      (println "usage: harvest.cljs <ndl|loc|korea-nl> \"<query>\" [max-records]")
      (js/process.exit 1))))
