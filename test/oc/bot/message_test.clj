(ns oc.bot.message-test
  (:require
   [clojure.java.io :as io]
   [oc.bot.message :as m]
   [clojure.test :as test :refer [deftest is]]))

(deftest humanize-test
  (is (= "EUR (€)" (#'m/humanize :oc.bot.conversation/eur)))
  (is (= "USD ($)" (#'m/humanize :oc.bot.conversation/usd))))

(deftest render-test
  (is (= "hello sarah" (#'m/render "hello {{name}}" {:name "sarah"})))
  (is (= "hello sarah" (#'m/render "hello {{user/name}}" {:user/name "sarah"})))
  (is (= "the currency is EUR (€)" (#'m/render "the currency is {{currency}}" {:currency :oc.bot.conversation/eur})))
  (is (= "the currency is USD ($)" (#'m/render "the currency is {{currency}}" {:currency :oc.bot.conversation/usd})))
  (is (thrown? clojure.lang.ExceptionInfo (#'m/render "hello {{name}}" {})))
  (is (thrown? clojure.lang.ExceptionInfo (#'m/render "hello {{name}}" {:foo "bar"}))))

(deftest get-messages-test
  (let [tpls {:my-script {[:stage :transition-1] ["abc" "efg" ["1-hijkl" "2-hijkl"]]}}
        msgs (#'m/get-messages tpls :my-script [:stage :transition-1])]
    (is (or (= ["abc" "efg" "1-hijkl"] msgs)
            (= ["abc" "efg" "2-hijkl"] msgs)))
    (is (thrown? clojure.lang.ExceptionInfo (#'m/get-messages tpls :my-script [:stage :foo])))))

;; this should be tested as well but currently it relies in the global
;; templates var which is a function for development and should be statically
;; defined for production. Since we don't want to make #'m/templates dynamic
;; the approach below doesn't work either.
;; (deftest messages-for-test
;;   (with-bindings [#'m/templates (fn [] {:my-script {[:stage :transition-1] ["hello {{name}}"]}})]
;;     (is (= ["hello sarah"]
;;            (m/messages-for :my-script [:stage :transition-1] {:name "sarah"})))))

(comment
  (test/run-tests)

  )

