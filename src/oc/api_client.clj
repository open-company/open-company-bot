(ns oc.api-client
  (:require [aleph.http :as http]
            [taoensso.timbre :as timbre]
            [cheshire.core :as cheshire]
            [manifold.deferred :as d]
            [clojure.string :as string]
            [environ.core :as e]
            [byte-streams :as bs]))

;; curl -i -X PATCH \
;; -d '{"currency": "FKP" }' \
;; --header "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1c2VyLWlkIjoiMTIzNDU2IiwibmFtZSI6ImNveW90ZSIsInJlYWwtbmFtZSI6IldpbGUgRS4gQ295b3RlIiwiYXZhdGFyIjoiaHR0cDpcL1wvd3d3LmVtb3RpY29uc3dhbGxwYXBlcnMuY29tXC9hdmF0YXJcL2NhcnRvb25zXC9XaWxleS1Db3lvdGUtRGF6ZWQuanBnIiwiZW1haWwiOiJ3aWxlLmUuY295b3RlQGFjbWUuY29tIiwib3duZXIiOmZhbHNlLCJhZG1pbiI6ZmFsc2UsIm9yZy1pZCI6Ijk4NzY1In0.HwqwEijPYDXTLdnL0peO8_KEtj379s4P5oJyv06yhfU" \
;; --header "Accept: application/vnd.open-company.company.v1+json" \
;; --header "Accept-Charset: utf-8" \
;; --header "Content-Type: application/vnd.open-company.company.v1+json" \
;; http://localhost:3000/companies/hotel-procrastination

;; (def +api-host+ (e/env :oc-api))
(def +api-host+ "http://localhost:3000")

(def jwt "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1c2VyLWlkIjoic2xhY2s6VTA2U0JUWEpSIiwibmFtZSI6IlNlYW4gSm9obnNvbiIsInJlYWwtbmFtZSI6IlNlYW4gSm9obnNvbiIsImF2YXRhciI6Imh0dHBzOlwvXC9zZWN1cmUuZ3JhdmF0YXIuY29tXC9hdmF0YXJcL2Y1YjhmYzFhZmZhMjY2YzgwNzIwNjhmODExZjYzZTA0LmpwZz9zPTE5MiZkPWh0dHBzJTNBJTJGJTJGc2xhY2suZ2xvYmFsLnNzbC5mYXN0bHkubmV0JTJGN2ZhOSUyRmltZyUyRmF2YXRhcnMlMkZhdmFfMDAyMC0xOTIucG5nIiwiZW1haWwiOiJzZWFuQG9wZW5jb21wYW55LmNvbSIsIm93bmVyIjpmYWxzZSwiYWRtaW4iOnRydWUsIm9yZy1pZCI6InNsYWNrOlQwNlNCTUg2MCJ9.9Q8GNBojQ_xXT0lMtKve4fb5Pdh260oc2aUc-wP8dus")

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

(defn patch-company! [slug data]
  (timbre/info "Updating Company:" slug data)
  (-> (http/patch (str +api-host+ "/companies/" slug)
                  {:middleware wrap-json
                   :headers {"Authorization" jwt
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
