#!/usr/bin/env nbb
;; usage: npx nbb --classpath "src:test" scripts/run-tests.cljs
(require '[clojure.test :as t] 'toshokan.ndl-test 'toshokan.loc-test 'toshokan.dnb-test)
(let [{:keys [fail error]} (t/run-tests 'toshokan.ndl-test 'toshokan.loc-test 'toshokan.dnb-test)]
  (js/process.exit (if (pos? (+ fail error)) 1 0)))
