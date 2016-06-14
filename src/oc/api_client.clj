(ns oc.api-client
  (:require [aleph.http :as http]
            [taoensso.timbre :as timbre]
            [cheshire.core :as cheshire]
            [manifold.deferred :as d]
            [clojure.string :as string]
            [environ.core :as e]
            [byte-streams :as bs]))

(def +api-host+ (e/env :oc-api-endpoint))

(def oc-json-types
  ["application/vnd.open-company.company.v1+json"
   "application/vnd.collection+vnd.open-company.company+json"
   "application/json"])

(defn json-type? [r]
  (let [ct (or (get-in r [:headers "content-type"])
               (get-in r [:headers "Content-Type"]))]
    (some #(string/starts-with? ct %) oc-json-types)))

(defn wrap-json [handler]
  (fn [request]
    (let [->json  cheshire/generate-string
          parse-bs (comp #(cheshire/parse-string % true) bs/to-string)
          res      (handler (cond-> request (json-type? request) (update :body ->json)))]
      (d/chain res #(cond-> % (json-type? %) (update :body parse-bs))))))

(defn patch-company! [token slug data]
  (timbre/info "Updating Company:" slug data)
  ;; In development the API may contain huge HTTP headers which makes
  ;; aleph block indefinitely: https://github.com/ztellman/aleph/issues/239
  ;; a temporary solution to this is to desable the liberator trace headers
  (-> (http/patch (str +api-host+ "/companies/" slug)
                  {:middleware wrap-json
                   :headers {"Authorization" (str "Bearer " token)
                             "Accept" "application/vnd.open-company.company.v1+json"
                             "Accept-Charset" "utf-8"
                             "Content-Type" "application/vnd.open-company.company.v1+json"}
                   :body data})
      (d/chain (fn [response] (timbre/info "Company Updated:" (-> response :body :slug)) response) :body)
      (d/catch (fn [err] (timbre/error err)))))

(comment 
  @(patch-company! "buffer" {:description "Yada Yada"})

  (-> (patch-company! "buffer" {:description "Yada Yada"})
      (d/chain :body prn)
      (d/catch #(prn (ex-data %))))

;; (#'http/def-http-method 'patch)

  (deref (http/get (str +api-host+ "/")))

  (deref (http/get (str +api-host+ "/companies/bago")))

  (#'http/reg uri)

  )