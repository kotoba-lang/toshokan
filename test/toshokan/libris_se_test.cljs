(ns toshokan.libris-se-test
  (:require ["node:fs" :as fs]
            [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [toshokan.sources.libris-se :as libris]))

(def sample-xml (fs/readFileSync "test/fixtures/libris-se-xsearch-sample.xml" "utf8"))

(deftest parses-all-records-including-one-with-no-libris99-id
  (let [recs (libris/parse-records sample-xml)]
    (is (= 3 (count recs)))
    (is (every? :title recs))))

(deftest excludes-subject-person-from-creators
  ;; real fixture: record 1's <subject><name><namePart>Natsume, Sōseki
  ;; is the book's SUBJECT, not its author (that's Yu, Beongcheon) --
  ;; regression guard against misreading subject names as creators.
  (let [r (first (libris/parse-records sample-xml))]
    (is (= "libris-se:9903245734" (:entity r)))
    (is (= ["Yu, Beongcheon"] (:creators r)))
    (is (not (some #(str/includes? % "Sōseki") (:creators r))))))

(deftest keeps-multiple-real-creators
  (let [r (second (libris/parse-records sample-xml))]
    (is (= 3 (count (:creators r))))
    (is (= ["9784805317747"] (:isbn r)))))

(deftest falls-back-to-record-identifier-when-no-libris99-id
  (let [r (nth (libris/parse-records sample-xml) 2)]
    (is (= "libris-se:0gkpc07qx4lv6qg3" (:entity r)))
    (is (= [] (:creators r)))))

(deftest quads-cover-required-attrs
  (let [r (first (libris/parse-records sample-xml))
        quads (libris/->quads 1 "2026-07-19T00:00:00Z" r)
        attrs (set (map second quads))]
    (is (contains? attrs :library/title))
    (is (contains? attrs :library/source))))
