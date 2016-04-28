(ns oc.api-client
  (:require [aleph.http :as http]
            [taoensso.timbre :as timbre]
            [cheshire.core :as cheshire]
            [manifold.deferred :as d]
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

(def jwtoken-coyote "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1c2VyLWlkIjoic2xhY2s6MTIzNDU2IiwibmFtZSI6ImNveW90ZSIsInJlYWwtbmFtZSI6IldpbGUgRS4gQ295b3RlIiwiYXZhdGFyIjoiaHR0cDpcL1wvd3d3LmVtb3RpY29uc3dhbGxwYXBlcnMuY29tXC9hdmF0YXJcL2NhcnRvb25zXC9XaWxleS1Db3lvdGUtRGF6ZWQuanBnIiwiZW1haWwiOiJ3aWxlLmUuY295b3RlQGFjbWUuY29tIiwib3duZXIiOmZhbHNlLCJhZG1pbiI6ZmFsc2UsIm9yZy1pZCI6InNsYWNrOjk4NzY1In0.1gQWBUhsfWjmvwWeK_BiyjVLbryKTAVNElj5BJkoH0o")
(def jwtoken-camus  "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1c2VyLWlkIjoic2xhY2s6MTk2MC0wMS0wNCIsIm5hbWUiOiJjYW11cyIsInJlYWwtbmFtZSI6IkFsYmVydCBDYW11cyIsImF2YXRhciI6Imh0dHA6XC9cL3d3dy5icmVudG9uaG9sbWVzLmNvbVwvd3AtY29udGVudFwvdXBsb2Fkc1wvMjAxMFwvMDVcL2FsYmVydC1jYW11czEuanBnIiwiZW1haWwiOiJhbGJlcnRAY29tYmF0Lm9yZyIsIm93bmVyIjp0cnVlLCJhZG1pbiI6dHJ1ZSwib3JnLWlkIjoic2xhY2s6OTg3NjUifQ.qWIlI7WztAGC2x9QXcy2_ZhgGnCt-6MfK6xZjk8OPmE")
(def jwtoken-sartre "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1c2VyLWlkIjoic2xhY2s6MTk4MC0wNi0yMSIsIm5hbWUiOiJzYXJ0cmUiLCJyZWFsLW5hbWUiOiJKZWFuLVBhdWwgU2FydHJlIiwiYXZhdGFyIjoiaHR0cDpcL1wvZXhpc3RlbnRpYWxpc210b2RheS5jb21cL3dwLWNvbnRlbnRcL3VwbG9hZHNcLzIwMTVcLzExXC9zYXJ0cmVfMjIuanBnIiwiZW1haWwiOiJzYXJ0cmVAbHljZWVsYS5vcmciLCJvd25lciI6dHJ1ZSwiYWRtaW4iOnRydWUsIm9yZy1pZCI6InNsYWNrOjg3NjU0In0.Cneyfu5WFHvgSCyV4wn-L-ztZ5q_vu1ElnbShCA8Y9w")
(def jwt            "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1c2VyLWlkIjoiMTIzNDU2IiwibmFtZSI6ImNveW90ZSIsInJlYWwtbmFtZSI6IldpbGUgRS4gQ295b3RlIiwiYXZhdGFyIjoiaHR0cDpcL1wvd3d3LmVtb3RpY29uc3dhbGxwYXBlcnMuY29tXC9hdmF0YXJcL2NhcnRvb25zXC9XaWxleS1Db3lvdGUtRGF6ZWQuanBnIiwiZW1haWwiOiJ3aWxlLmUuY295b3RlQGFjbWUuY29tIiwib3duZXIiOmZhbHNlLCJhZG1pbiI6ZmFsc2UsIm9yZy1pZCI6Ijk4NzY1In0.HwqwEijPYDXTLdnL0peO8_KEtj379s4P5oJyv06yhfU")

(defn wrap-json [handler]
  (fn [request]
    (let [res (handler (update request :body cheshire/generate-string))]
      (d/chain res #(update % :body (comp cheshire/parse-string bs/to-string))))))

(defn patch-company! [slug data]
  (timbre/info "Updating Company:" slug data)
  (-> (http/patch (str +api-host+ "/companies/" slug)
                  {:middleware wrap-json
                   :headers {"Authorization" jwt
                             "Accept" "application/vnd.open-company.company.v1+json"
                             "Accept-Charset" "utf-8"
                             "Content-Type" "application/vnd.open-company.company.v1+json"}
                   :body data})
      (d/chain (fn [response] (timbre/info "Company Updated:" response)))
      (d/catch (fn [err] (timbre/error err)))))

(comment 
  @(patch-company! "buffer" {:description "Yada Yada"})

  (-> (patch-company! "buffer" {:description "Yada Yada"})
      (d/chain :body prn)
      (d/catch #(prn (ex-data %))))

;; (#'http/def-http-method 'patch)

  (deref (http/get (str +api-host+ "/companies")))

  (deref (http/get (str +api-host+ "/companies/bago")))

  (#'http/reg uri)

  )
