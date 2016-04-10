(defproject mkremins/twaudit "0.1.0-SNAPSHOT"
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [mkremins/twitter-oauth "0.1.0"]
                 [clj-time "0.11.0"]
                 [compojure "1.3.4"]
                 [selmer "0.8.2"]
                 [twitter-api "0.7.8"]
                 [org.slf4j/slf4j-nop "1.7.12"]]
  :plugins [[lein-ring "0.9.5"]]
  :ring {:handler twaudit.app/app})
