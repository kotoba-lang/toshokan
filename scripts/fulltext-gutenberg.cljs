#!/usr/bin/env nbb
;; fulltext-gutenberg.cljs — Project Gutenberg public-domain full text.
;;
;; Uses gutendex.com (JSON, no key) which exposes `copyright: false` for
;; public-domain works. Only those are downloaded. Body goes under
;; fulltext/gutenberg/<id>/ (git-annex); bibliographic quads go to
;; 80-data/public/gutenberg.journal.edn (plain git, same as other sources).
;;
;; Usage (repo root):
;;   nbb --classpath src scripts/fulltext-gutenberg.cljs --seed "Pride and Prejudice" --limit 2
;;   nbb --classpath src scripts/fulltext-gutenberg.cljs --id 1342
;;   nbb --classpath src scripts/fulltext-gutenberg.cljs --from-seeds --limit 1

(ns fulltext-gutenberg
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:crypto" :as crypto]
            ["node:child_process" :as cp]
            [clojure.string :as str]
            [cljs.reader :as edn]
            [toshokan.quad :as quad]))

(def gutendex "https://gutendex.com/books/")
(def ua "toshokan-library-harvester/0.1 (kotoba-lang/toshokan; public-domain fulltext; https://github.com/kotoba-lang/toshokan)")
(def journal-path (path/join "80-data" "public" "gutenberg.journal.edn"))
(def fulltext-root (path/join "fulltext" "gutenberg"))

(defn- ensure-dir! [p]
  (fs/mkdirSync p #js {:recursive true}))

(defn- sha256-hex [buf]
  (-> (.createHash crypto "sha256") (.update buf) .digest (.toString "hex")))

(defn- known-ids []
  (->> (quad/read-journal journal-path)
       (filter #(= (second %) :library/gutenberg-id))
       (map #(str (nth % 2)))
       set))

(defn- fetch-json [url]
  (-> (js/fetch url #js {:headers #js {"User-Agent" ua}})
      (.then (fn [^js r]
               (if (.-ok r)
                 (.json r)
                 (throw (js/Error. (str "HTTP " (.-status r) " " url))))))
      (.then #(js->clj % :keywordize-keys true))))

(defn- fetch-bytes [url]
  (-> (js/fetch url #js {:headers #js {"User-Agent" ua}})
      (.then (fn [^js r]
               (if (.-ok r)
                 (.arrayBuffer r)
                 (throw (js/Error. (str "HTTP " (.-status r) " " url))))))
      (.then (fn [ab] (js/Buffer.from ab)))))

(defn- plain-text-urls
  "Candidate plain-text body URLs for a gutendex book map, highest preference
   first. Multiple fallbacks needed because gutendex format URLs and the
   canonical cache path sometimes 404 (seen on e.g. pg76404)."
  [book]
  (let [fm (:formats book)
        entries (if (map? fm) (seq fm) nil)
        id (:id book)
        from-formats
        (into []
              (keep (fn [[k v]]
                      (let [ks (str k)]
                        (when (and v
                                   (str/includes? ks "text/plain")
                                   (not (str/includes? ks "zip")))
                          v)))
                    entries))
        ;; prefer utf-8 listed first
        utf8 (filterv #(or (str/includes? (str %) "utf-8")
                           (str/includes? (str %) "utf8"))
                      from-formats)
        other (filterv #(not (or (str/includes? (str %) "utf-8")
                                 (str/includes? (str %) "utf8")))
                       from-formats)
        fallbacks (when id
                    [(str "https://www.gutenberg.org/cache/epub/" id "/pg" id ".txt")
                     (str "https://www.gutenberg.org/files/" id "/" id "-0.txt")
                     (str "https://www.gutenberg.org/files/" id "/" id "-8.txt")
                     (str "https://www.gutenberg.org/files/" id "/" id ".txt")
                     (str "https://www.gutenberg.org/ebooks/" id ".txt.utf-8")])]
    (->> (concat utf8 other fallbacks)
         (remove str/blank?)
         ;; gutendex sometimes lists audio-book "readme" stubs as text/plain
         (remove #(or (str/includes? (str %) "readme")
                      (str/includes? (str %) "-readme.")
                      (str/includes? (str %) ".readme")))
         distinct
         vec)))
(defn- plain-text-url [book]
  (first (plain-text-urls book)))

(defn- book->fields [book]
  (let [id (str (:id book))
        authors (map :name (:authors book))
        subjects (:subjects book)
        urls (plain-text-urls book)]
    {:entity (str "gutenberg:" id)
     :gutenberg-id id
     :title (:title book)
     :creators authors
     :subjects subjects
     :languages (:languages book)
     :media-type (:media_type book)
     :download-count (:download_count book)
     :copyright? (boolean (:copyright book))
     :source-url (str "https://www.gutenberg.org/ebooks/" id)
     :text-url (first urls)
     :text-urls urls}))
(defn- write-body! [fields buf]
  (let [id (:gutenberg-id fields)
        dir (path/join fulltext-root id)
        txt-path (path/join dir (str "pg" id ".txt"))
        meta-path (path/join dir "meta.edn")
        digest (sha256-hex buf)]
    (ensure-dir! dir)
    (fs/writeFileSync txt-path buf)
    (fs/writeFileSync
     meta-path
     (str (pr-str {:gutenberg/id id
                   :library/title (:title fields)
                   :library/source-url (:source-url fields)
                   :fulltext/url (:text-url fields)
                   :fulltext/sha256 digest
                   :fulltext/bytes (.-length buf)
                   :fulltext/retrieved-at (.toISOString (js/Date.))
                   :fulltext/license :public-domain
                   :fulltext/source :project-gutenberg})
          "\n"))
    {:path txt-path :sha256 digest :bytes (.-length buf)}))

(defn- append-quads! [fields body-info]
  (let [existing (quad/read-journal journal-path)
        tx (quad/next-tx existing)
        retrieved-at (.toISOString (js/Date.))
        quads (quad/record->quads
               (:entity fields) tx
               {:library/source :gutenberg
                :library/source-url (:source-url fields)
                :library/title (:title fields)
                :library/creator (:creators fields)
                :library/subject (:subjects fields)
                :library/language (:languages fields)
                :library/gutenberg-id (:gutenberg-id fields)
                :library/copyright false
                :library/fulltext-path (:path body-info)
                :library/fulltext-sha256 (:sha256 body-info)
                :library/fulltext-bytes (:bytes body-info)
                :library/fulltext-license :public-domain
                :library/retrieved-at retrieved-at})]
    (ensure-dir! (path/dirname journal-path))
    (quad/append-journal! journal-path quads)
    (count quads)))

(defn ingest-book!
  "Download one gutendex book map if public-domain + has plain text."
  [book]
  (let [fields (book->fields book)
        known (known-ids)]
    (cond
      (:copyright? fields)
      (do (println "[fulltext] SKIP copyrighted id=" (:gutenberg-id fields)
                   (pr-str (:title fields)))
          (js/Promise.resolve {:ok false :reason :copyright}))

      (contains? known (:gutenberg-id fields))
      (do (println "[fulltext] SKIP already have id=" (:gutenberg-id fields))
          (js/Promise.resolve {:ok false :reason :duplicate}))

      (empty? (:text-urls fields))
      (do (println "[fulltext] SKIP no plain-text format id=" (:gutenberg-id fields))
          (js/Promise.resolve {:ok false :reason :no-text}))

      :else
      ;; Try each candidate URL until one succeeds (404 on cache path is common).
      ((fn try-url [urls]
         (if-not (seq urls)
           (do (println "[fulltext] SKIP all text urls failed id="
                        (:gutenberg-id fields))
               (js/Promise.resolve {:ok false :reason :all-urls-failed}))
           (-> (fetch-bytes (first urls))
               (.then
                (fn [buf]
                  (let [fields* (assoc fields :text-url (first urls))
                        body (write-body! fields* buf)
                        nq (append-quads! fields* body)]
                    (println "[fulltext] OK id=" (:gutenberg-id fields)
                             "title=" (pr-str (:title fields))
                             "bytes=" (:bytes body)
                             "quads=" nq
                             "url=" (first urls))
                    {:ok true :id (:gutenberg-id fields) :bytes (:bytes body)
                     :title (:title fields)})))
               (.catch
                (fn [e]
                  (println "[fulltext] try-fail" (first urls) (.-message e))
                  (try-url (rest urls)))))))
       (:text-urls fields)))))

(defn search-books
  "Fetch public-domain gutendex hits for q. Pulls a larger page than `limit`
   so callers can skip already-held ids and still fill the requested quota."
  [q limit]
  (let [page-size (max 32 (* 4 (or limit 2)))]
    (-> (fetch-json (str gutendex "?search=" (js/encodeURIComponent q)
                         "&copyright=false"))
        (.then (fn [resp]
                 (->> (:results resp)
                      (remove :copyright)
                      (take page-size)
                      vec))))))

(defn fetch-book-by-id [id]
  (fetch-json (str gutendex id)))

(defn- cjk-query?
  "Gutenberg has near-zero CJK catalog hits; skip pure CJK seed strings."
  [q]
  (boolean (re-find #"[\u3040-\u30ff\u3400-\u9fff]" (str q))))

(defn- seeds-from-file []
  (let [m (try (edn/read-string (fs/readFileSync "seeds.edn" "utf8"))
               (catch :default _ {:seeds []}))]
    (->> (:seeds m)
         ;; hand seeds first (same schedule discipline as daemon catalog)
         (remove :grown-from)
         (map :query)
         (remove str/blank?)
         (remove cjk-query?)
         vec)))

(defn- rotate-take
  "Round-robin window over qs so --from-seeds does not forever re-query the
   first 8 classics we already hold (was the main fulltext stall)."
  [qs n offset]
  (let [m (count qs)]
    (if (or (zero? m) (zero? n))
      []
      (let [start (mod (or offset 0) m)]
        (vec (for [i (range (min n m))]
               (nth qs (mod (+ start i) m))))))))

(defn- parse-args [argv]
  (loop [xs argv acc {:limit 2 :from-seeds? false :seeds [] :ids []}]
    (if-not (seq xs)
      acc
      (let [[a & more] xs]
        (case a
          "--limit" (recur (rest more)
                           (assoc acc :limit (js/parseInt (first more) 10)))
          "--seed" (recur (rest more)
                          (update acc :seeds conj (first more)))
          "--id" (recur (rest more)
                        (update acc :ids conj (first more)))
          "--from-seeds" (recur more (assoc acc :from-seeds? true))
          (recur more acc))))))

(defn- run-sequential [promise-fns]
  (reduce (fn [chain f]
            (.then chain (fn [acc]
                           (-> (f)
                               (.then (fn [r] (conj acc r)))
                               (.catch (fn [e]
                                         (println "[fulltext] error" (.-message e))
                                         (conj acc {:ok false :error (.-message e)})))))))
          (js/Promise.resolve [])
          promise-fns))

(defn -main []
  (let [argv (js->clj js/process.argv)
        idx (or (some (fn [[i a]] (when (str/ends-with? a "fulltext-gutenberg.cljs") i))
                      (map-indexed vector argv))
                2)
        opts (parse-args (drop (inc idx) argv))
        known (known-ids)
        ;; Offset rotation by how many fulltexts we already hold so each tick
        ;; advances the seed window without needing extra state.
        seed-offset (count known)
        file-seeds (when (:from-seeds? opts) (seeds-from-file))
        seed-qs (cond-> (vec (:seeds opts))
                  (seq file-seeds)
                  (into (rotate-take file-seeds 24 seed-offset)))
        seed-qs (if (and (empty? seed-qs) (empty? (:ids opts)))
                  ["Pride and Prejudice" "Iliad" "Origin of Species"
                   "Romeo and Juliet" "Divine Comedy" "Moby Dick"
                   "Frankenstein" "Don Quixote" "Candide" "Beowulf"]
                  seed-qs)
        want (or (:limit opts) 1)]
    (println "[fulltext] start"
             (pr-str (assoc opts :resolved-seeds seed-qs
                            :known-count (count known)
                            :seed-offset seed-offset)))
    (-> (run-sequential
         (concat
          (for [id (:ids opts)]
            (fn []
              (-> (fetch-book-by-id id)
                  (.then ingest-book!))))
          (for [q seed-qs]
            (fn []
              (-> (search-books q want)
                  (.then (fn [books]
                           (let [fresh (->> books
                                            (remove #(contains? known (str (:id %))))
                                            (take want)
                                            vec)]
                             (println "[fulltext] search" (pr-str q)
                                      "hits=" (count books)
                                      "fresh=" (count fresh))
                             (if (seq fresh)
                               (run-sequential
                                (map (fn [b] (fn [] (ingest-book! b))) fresh))
                               (js/Promise.resolve
                                [{:ok false :reason :all-held-or-empty}])))))
                  (.then (fn [rs] {:query q :results rs})))))))
        (.then
         (fn [summary]
           (println "[fulltext] done" (pr-str summary))
           summary)))))

(-main)
