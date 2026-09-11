(ns watchlist.adapters.eu-consolidated-test
  (:require [clojure.test :refer [deftest is]]
            [watchlist.adapters.eu-consolidated :as eu]))

(deftest parse-throws-with-a-clear-not-implemented-reason
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                         #"not implemented" (eu/parse "<anything/>"))))
