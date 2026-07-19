(ns toshokan.sources.ndl
  "National Diet Library (NDL, 国立国会図書館) SRU harvester.

  Endpoint: https://ndlsearch.ndl.go.jp/api/sru (verified live 2026-07-19,
  no auth/registration required for non-commercial use per
  https://ndlsearch.ndl.go.jp/en/help/api). recordSchema=dcndl returns
  DC-NDL v2 (RDF/XML), HTML-entity-escaped inside each <recordData>
  element. This namespace unescapes and regex-extracts the handful of
  fields toshokan cares about rather than pulling in a full XML/DOM
  parser dependency -- adequate for this stable, well-documented schema;
  revisit with a real parser if NDL's field shape drifts.

  NDL asks non-commercial callers to attribute \"NDL Search API\" and to
  avoid sustained high-concurrency traffic (no fixed numeric rate limit is
  published) -- this namespace issues one sequential request per call, no
  internal concurrency."
  (:require [clojure.string :as str]
            [toshokan.quad :as quad]))

(def ^:const sru-endpoint "https://ndlsearch.ndl.go.jp/api/sru")
(def ^:const source-key :ndl)

(defn- unescape-xml [s]
  (-> s
      (str/replace "&lt;" "<")
      (str/replace "&gt;" ">")
      (str/replace "&quot;" "\"")
      (str/replace "&apos;" "'")
      (str/replace "&#39;" "'")
      (str/replace "&amp;" "&")))

(defn- tag1 [tag block]
  (some-> (re-find (re-pattern (str "<" tag "[^>]*>([^<]*)</" tag ">")) block)
          second
          str/trim
          not-empty))

(defn- blocks [tag s]
  (re-seq (re-pattern (str "<" tag "[^>]*>[\\s\\S]*?</" tag ">")) s))

(defn- bib-resources [rdf-xml]
  (blocks "dcndl:BibResource" rdf-xml))

(defn- record-datas [sru-xml]
  (map second (re-seq #"<recordData>([\s\S]*?)</recordData>" sru-xml)))

(defn- entity-id [about-url]
  (when about-url
    (-> about-url
        (str/replace #"#.*$" "")
        (str/split #"/")
        last
        (->> (str "ndl:")))))

(defn parse-bib-resource
  "One <dcndl:BibResource rdf:about=...>...</dcndl:BibResource> block ->
  a field map, or nil if it has no usable title."
  [block]
  (let [about (some-> (re-find #"rdf:about=\"([^\"]+)\"" block) second)
        title (or (tag1 "dcterms:title" block) (tag1 "rdf:value" block))
        creators (->> (blocks "dcterms:creator" block)
                      (keep #(tag1 "foaf:name" %)))
        publishers (->> (blocks "dcterms:publisher" block)
                        (keep #(tag1 "foaf:name" %)))
        issued (or (tag1 "dcterms:issued" block) (tag1 "dcterms:date" block))
        ndc (some-> (re-find #"dc:subject rdf:datatype=\"[^\"]*NDC[^\"]*\">([^<]*)<" block)
                    second str/trim not-empty)
        extent (tag1 "dcterms:extent" block)]
    (when (and about title)
      {:entity (entity-id about)
       :source-url about
       :title title
       :creators creators
       :publishers publishers
       :issued issued
       :ndc ndc
       :extent extent})))

(defn parse-records
  "Full SRU response XML text -> seq of field-maps (dedup'd by entity, first
  BibResource per <recordData> block -- a record may repeat itself as an
  NDL record + a partner-library holding record, this keeps only the
  primary one)."
  [sru-xml-text]
  (->> (record-datas sru-xml-text)
       ;; NDL double-escapes literal quote marks inside titles (source text
       ;; has &amp;quot; for a literal " character) -- a second pass fixes
       ;; that without harming already-single-escaped content elsewhere.
       (map (comp unescape-xml unescape-xml))
       (mapcat (comp (partial take 1) bib-resources))
       (keep parse-bib-resource)))

(defn search
  "Returns a JS Promise of a seq of field-maps for `query` (SRU CQL, e.g.
  `(str \"title=\\\"\" q \"\\\"\")`)."
  [query & {:keys [max-records] :or {max-records 20}}]
  (-> (js/fetch (str sru-endpoint
                     "?operation=searchRetrieve&version=1.2"
                     "&query=" (js/encodeURIComponent query)
                     "&recordSchema=dcndl"
                     "&maximumRecords=" max-records)
                #js {:headers #js {"User-Agent" "toshokan-library-harvester/0.1 (kotoba-lang/toshokan; public bibliographic metadata preservation; https://github.com/kotoba-lang/toshokan)"}})
      (.then (fn [^js r]
               (if (.-ok r)
                 (.text r)
                 (throw (js/Error. (str "NDL SRU HTTP " (.-status r)))))))
      (.then parse-records)))

(defn ->quads
  "field-map (as returned by search/parse-records) + retrieved-at ISO
  string + tx -> seq of [entity attr value tx :add] quads."
  [tx retrieved-at m]
  (quad/record->quads
   (:entity m) tx
   {:library/source source-key
    :library/source-url (:source-url m)
    :library/title (:title m)
    :library/creator (:creators m)
    :library/publisher (:publishers m)
    :library/date (:issued m)
    :library/ndc (:ndc m)
    :library/extent (:extent m)
    :library/retrieved-at retrieved-at}))
