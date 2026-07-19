(ns toshokan.dnb-test
  (:require ["node:fs" :as fs]
            [clojure.test :refer [deftest is]]
            [toshokan.sources.dnb :as dnb]))

(def sample-xml (fs/readFileSync "test/fixtures/dnb-sru-sample.xml" "utf8"))

(deftest parses-records
  (let [recs (dnb/parse-records sample-xml)]
    (is (= 3 (count recs)))
    (is (every? #(re-matches #"^dnb:\d+$" (:entity %)) recs))
    (is (every? :title recs))))

(deftest parses-identifiers-and-multi-valued-fields
  (let [r (first (dnb/parse-records sample-xml))]
    (is (= "dnb:1395072884" (:entity r)))
    (is (= ["978-3-99165-215-1" "3-99165-215-3"] (:isbn r)))
    (is (= 3 (count (:publishers r))))
    (is (= "ger" (:language r)))))

(deftest quads-cover-required-attrs
  (let [r (first (dnb/parse-records sample-xml))
        quads (dnb/->quads 1 "2026-07-19T00:00:00Z" r)
        attrs (set (map second quads))]
    (is (contains? attrs :library/title))
    (is (contains? attrs :library/source))
    (is (contains? attrs :library/isbn))
    (is (every? #(= "dnb:1395072884" (first %)) quads))))
