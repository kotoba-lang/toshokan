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

(defn- plain-text-url [book]
  (let [fm (:formats book)
        entries (if (map? fm) (seq fm) nil)]
    (or
     ;; preferred: utf-8 plain text (not zip)
     (some (fn [[k v]]
             (let [ks (str k)]
               (when (and v
                          (str/includes? ks "text/plain")
                          (str/includes? ks "utf-8")
                          (not (str/includes? ks "zip")))
                 v)))
           entries)
     (some (fn [[k v]]
             (let [ks (str k)]
               (when (and v
                          (str/includes? ks "text/plain")
                          (not (str/includes? ks "zip")))
                 v)))
           entries)
     ;; fallback: well-known Gutenberg mirror path
     (when-let [id (:id book)]
       (str "https://www.gutenberg.org/cache/epub/" id "/pg" id ".txt")))))

(defn- book->fields [book]
  (let [id (str (:id book))
        authors (map :name (:authors book))
        subjects (:subjects book)]
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
     :text-url (plain-text-url book)}))

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

      (str/blank? (:text-url fields))
      (do (println "[fulltext] SKIP no plain-text format id=" (:gutenberg-id fields))
          (js/Promise.resolve {:ok false :reason :no-text}))

      :else
      (-> (fetch-bytes (:text-url fields))
          (.then
           (fn [buf]
             (let [body (write-body! fields buf)
                   nq (append-quads! fields body)]
               (println "[fulltext] OK id=" (:gutenberg-id fields)
                        "title=" (pr-str (:title fields))
                        "bytes=" (:bytes body)
                        "quads=" nq)
               {:ok true :id (:gutenberg-id fields) :bytes (:bytes body)
                :title (:title fields)})))))))

(defn search-books [q limit]
  (-> (fetch-json (str gutendex "?search=" (js/encodeURIComponent q)
                       "&copyright=false"))
      (.then (fn [resp]
               (->> (:results resp)
                    (remove :copyright)
                    (take limit)
                    vec)))))

(defn fetch-book-by-id [id]
  (fetch-json (str gutendex id)))

(defn- seeds-from-file []
  (let [m (try (edn/read-string (fs/readFileSync "seeds.edn" "utf8"))
               (catch :default _ {:seeds []}))]
    (->> (:seeds m)
         (map :query)
         (remove str/blank?)
         vec)))

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
        seed-qs (cond-> (:seeds opts)
                  (:from-seeds? opts) (into (take 8 (seeds-from-file))))
        seed-qs (if (and (empty? seed-qs) (empty? (:ids opts)))
                  ["Pride and Prejudice" "Iliad" "Origin of Species"
                   "Romeo and Juliet" "Divine Comedy"]
                  seed-qs)]
    (println "[fulltext] start" (pr-str (assoc opts :resolved-seeds seed-qs)))
    (-> (run-sequential
         (concat
          (for [id (:ids opts)]
            (fn []
              (-> (fetch-book-by-id id)
                  (.then ingest-book!))))
          (for [q seed-qs]
            (fn []
              (-> (search-books q (:limit opts))
                  (.then (fn [books]
                           (println "[fulltext] search" (pr-str q)
                                    "hits=" (count books))
                           (run-sequential
                            (map (fn [b] (fn [] (ingest-book! b))) books))))
                  (.then (fn [rs] {:query q :results rs})))))))
        (.then
         (fn [summary]
           (println "[fulltext] done" (pr-str summary))
           summary)))))

(-main)
