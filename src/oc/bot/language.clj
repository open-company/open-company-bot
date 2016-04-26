(ns oc.bot.language)

(defn yes? [s]
  (#{"y" "yes"} s))

(defn no? [s]
  (#{"n" "no"} s))

(defn euro? [s]
  (#{"€" "eur" "euro"} s))

(defn dollar? [s]
  (#{"$" "usd" "dollar"} s))