(ns toshokan.sources.libris-se
  "Libris (Swedish National Library union catalog, KB) XSearch harvester.

  Endpoint: http://libris.kb.se/xsearch (verified live 2026-07-19, no
  auth/registration required; documented at
  https://www.kb.se/samverkan-och-utveckling/nytt-fran-kb/nyheter-samverkan-och-utveckling/2016-01-14-libris-xsearch.html).
  `format=dc` is NOT supported by this endpoint (\"format unsupported\");
  `format=mods` works and is used here. MODS nests <name> (creator) and
  <identifier> under a single element name with different attributes and
  contexts, so this parser is stricter than the plain-Dublin-Core sources:
  - creators are ONLY top-level <name><namePart> (no type attribute) --
    subject-authority person names also use <name><namePart> but nested
    inside <subject>...</subject>, so that block is stripped first to
    avoid misreading a book's subject person as its author (verified
    against real data: record 1 in the fixture has Natsume Sōseki as a
    <subject><name>, not a creator).
  - the primary identifier is <identifier type=\"libris99\">, with a
    fallback to <recordInfo><recordIdentifier> for records that lack one
    (real data: the fixture's periodical record has no libris99 id)."
  (:require [clojure.string :as str]
            [toshokan.quad :as quad]))

(def ^:const xsearch-endpoint "http://libris.kb.se/xsearch")
(def ^:const source-key :libris-se)

(defn- strip-subjects [block]
  (str/replace block #"<subject[^>]*>[\s\S]*?</subject>" ""))

(defn- plain-tag-values
  "Only matches the attribute-free opening tag, e.g. <dateIssued> but not
  <dateIssued encoding=\"marc\">, to pick the primary value among MODS's
  several typed variants of the same element name."
  [tag block]
  (->> (re-seq (re-pattern (str "<" tag ">([^<]*)</" tag ">")) block)
       (map (comp str/trim second))
       (remove str/blank?)))

(defn- attr-tag-values [tag block]
  (->> (re-seq (re-pattern (str "<" tag "[^>]*>([^<]*)</" tag ">")) block)
       (map (comp str/trim second))
       (remove str/blank?)))

(defn- typed-identifier [type-name block]
  (->> (re-seq (re-pattern (str "<identifier type=\"" type-name "\"[^>]*>([^<]*)</identifier>")) block)
       (map (comp str/trim second))
       (remove str/blank?)))

(defn- record-blocks [xsearch-xml]
  (re-seq #"<mods [\s\S]*?</mods>" xsearch-xml))

(defn parse-record [block]
  (let [creators (->> (plain-tag-values "namePart" (strip-subjects block)))
        libris-id (first (typed-identifier "libris99" block))
        record-id (or libris-id
                      (first (attr-tag-values "recordIdentifier" block)))
        titles (plain-tag-values "title" block)]
    (when (and record-id (seq titles))
      {:entity (str "libris-se:" record-id)
       :title (first titles)
       :creators creators
       :publishers (plain-tag-values "publisher" block)
       :date (first (plain-tag-values "dateIssued" block))
       :language (first (attr-tag-values "languageTerm" block))
       :isbn (typed-identifier "isbn" block)
       :extent (plain-tag-values "extent" block)})))

(defn parse-records [xsearch-xml-text]
  (keep parse-record (record-blocks xsearch-xml-text)))

(defn search
  "Returns a JS Promise of a seq of field-maps for `query` (free text)."
  [query & {:keys [max-records] :or {max-records 20}}]
  (-> (js/fetch (str xsearch-endpoint
                     "?query=" (js/encodeURIComponent query)
                     "&format=mods&n=" max-records)
                #js {:headers #js {"User-Agent" "toshokan-library-harvester/0.1 (kotoba-lang/toshokan; public bibliographic metadata preservation; https://github.com/kotoba-lang/toshokan)"}
                     :redirect "follow"})
      (.then (fn [^js r]
               (if (.-ok r)
                 (.text r)
                 (throw (js/Error. (str "Libris XSearch HTTP " (.-status r)))))))
      (.then parse-records)))

(defn ->quads
  [tx retrieved-at m]
  (quad/record->quads
   (:entity m) tx
   {:library/source source-key
    :library/title (:title m)
    :library/creator (:creators m)
    :library/publisher (:publishers m)
    :library/date (:date m)
    :library/language (:language m)
    :library/isbn (:isbn m)
    :library/extent (:extent m)
    :library/retrieved-at retrieved-at}))
