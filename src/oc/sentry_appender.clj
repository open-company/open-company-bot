;; mostly copied from https://github.com/yeller/yeller-timbre-appender/blob/master/src/yeller/timbre_appender.clj
;; Thanks Tom!

(ns oc.sentry-appender
  (:require [raven-clj.core :as sentry]
            [raven-clj.interfaces :as sentry-interfaces]))

(defn extract-ex-data [throwable]
  (if-let [data (ex-data throwable)]
    {:ex-data data}
    {}))

(defn extract-arg-data [raw-args]
  (if-let [m (first (filter map? raw-args))]
    m
    {}))

(defn extract-data [throwable raw-args]
  (let [arg-data (extract-arg-data raw-args)
        ex-data (extract-ex-data throwable)]
    (merge
      arg-data
      {:custom-data (merge ex-data (:custom-data arg-data {}))})))

(defn sentry-appender
  "Create a Sentry timbre appender.
   (make-sentry-appender {:dsn \"YOUR SENTRY DSN\"})"
  [options]
  (assert (:dsn options) "make-sentry-appender requires a :dsn")
  (merge
   {:doc "A timbre appender that sends errors to getsentry.com"
    :min-level :error
    :enabled? true
    :async? true
    :rate-limit nil
    :fn (fn [args]
          (let [throwable @(:?err_ args)
                data      (extract-data throwable @(:vargs_ args))]
            (when throwable
              (prn (-> {:message (.getMessage throwable)}
                       (assoc-in [:extra :exception-data] data)
                       (sentry-interfaces/stacktrace throwable)))
              (sentry/capture
               (:dsn options)
               (-> {:message (.getMessage throwable)}
                   (assoc-in [:extra :exception-data] data)
                   (sentry-interfaces/stacktrace throwable))))))}))

(comment
  ;; for repl testing
  (do (require '[taoensso.timbre :as timbre])
      (require '[oc.sentry-appender :reload true])
      (timbre/merge-config! {:appenders {:sentry-appender (oc.sentry-appender/sentry-appender {:dsn "https://2ee09cf318a14215af9350bf4151e302:affc13dc0b11466bab6546a190c29ddd@app.getsentry.com/76929"})}})
      (dotimes [_ 1]
        (timbre/error (ex-info "921392813" {:foo 1})
                      {:custom-data {:params {:user-id 1}}})))

  )