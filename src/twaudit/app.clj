(ns twaudit.app
  (:require [clj-time.coerce :as time-coerce]
            [clj-time.core :as time]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [compojure.core :refer [defroutes GET POST]]
            [compojure.handler :as handler]
            [twitter-oauth.middleware]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.session :as session]
            [ring.util.response :as response]
            [selmer.parser :as selmer]
            [twitter.api.restful :as twitter]))

;;; datetime parsing

(def parse-month
  {"Jan" 1 "Feb" 2 "Mar" 3 "Apr" 4 "May" 5 "Jun" 6
   "Jul" 7 "Aug" 8 "Sep" 9 "Oct" 10 "Nov" 11 "Dec" 12})

(defn parse-datetime [s]
  (when s
    ; Tue Apr 14 14:38:19 +0000 2015
    (let [[weekday month day time offset year] (str/split s #"\s+")
          [hour minute second] (str/split time #":")]
      (time/date-time (Integer/parseInt year)
                      (parse-month month)
                      (Integer/parseInt day)
                      (Integer/parseInt hour)
                      (Integer/parseInt minute)))))

(defn days-ago [datetime]
  (if datetime
    (let [days (time/in-days (time/interval datetime (time/now)))]
      (case days
        0 "today"
        1 "yesterday"
        (str days " days ago")))
    "never"))

;;; functions on user maps

(defn follows-me? [user]
  (boolean (some #{"followed_by"} (:connections user))))

(defn muted? [user]
  (boolean (some #{"muting"} (:connections user))))

(defn last-active [user]
  (-> user :status :created_at parse-datetime))

(defn profile-url [user]
  (str "https://twitter.com/" (:screen_name user)))

(defn reshape-user [user]
  (assoc user
    :last-active (days-ago (last-active user))
    :last-active-timestamp (time-coerce/to-long (last-active user))
    :muted? (muted? user)
    :mutual? (follows-me? user)
    :url (profile-url user)))

;;; friends data retrieval

(defn get-friends-ids [creds screen-name]
  (-> (twitter/friends-ids
        :oauth-creds creds
        :params {:screen-name screen-name})
      :body :ids))

(defn get-users [creds ids]
  (set/join (:body (twitter/friendships-lookup
                     :oauth-creds creds
                     :params {:user-id ids}))
            (:body (twitter/users-lookup
                     :oauth-creds creds
                     :verb :post
                     :params {:user-id ids}))
            {:id :id}))

(defn get-friends [creds screen-name]
  (let [friends (try
                  (->> (get-friends-ids creds screen-name)
                       (partition-all 100)
                       (take 15) ; limit to 15 batches (any more will hit friendships/lookup rate limit)
                       (map (partial str/join ","))
                       (mapcat (partial get-users creds))
                       (sort-by last-active)
                       (map reshape-user))
                  (catch Exception err
                    (println (str "get-friends: [error] " (.getMessage err)))
                    nil))]
    (println (str "get-friends: loaded " (count friends) " friends!"))
    friends))

(defn maybe-load-friends [twitter-data]
  (let [{:keys [friends friends-timestamp oauth-creds screen-name]
         :or   {friends-timestamp 0}} twitter-data
        current-timestamp (System/currentTimeMillis)
        friends-age (- current-timestamp friends-timestamp)]
    (cond-> twitter-data
      ;; if friends list is absent or stale (>15 mins old), retrieve new list
      (or (nil? friends) (> friends-age (* 15 60 1000)))
      (assoc :friends (get-friends oauth-creds screen-name)
             :friends-timestamp current-timestamp))))

;;; logging

(defn wrap-request-logging [handler]
  (fn [{method :request-method, uri :uri :as req}]
    (println (str (str/upper-case (name method)) " " uri))
    (handler req)))

;;; routes

(defn main-route [{:keys [session] :as req}]
  (if-let [twitter-data (some-> session :twitter maybe-load-friends)]
    (-> (selmer/render-file "index.html"
          (select-keys twitter-data [:friends :screen-name]))
        (response/response)
        (response/content-type "text/html;charset=utf-8")
        (assoc :session (assoc session :twitter twitter-data)))
    (str "You're not signed in to Twitter! <a href=\"/sign-in\">Click here to sign in.</a>")))

(defroutes app-routes
  (GET "/"             req (main-route req))
  (GET "/favicon.ico"  []  (response/not-found "404")))

;;; main app setup

(defn load-env [fpath]
  (let [consumer-key    (System/getenv "CONSUMER_KEY")
        consumer-secret (System/getenv "CONSUMER_SECRET")]
    (if (and consumer-key consumer-secret)
      {:consumer-key consumer-key :consumer-secret consumer-secret}
      (edn/read-string (slurp fpath)))))

(defn make-app [env]
  (-> app-routes
      (twitter-oauth.middleware/wrap-twitter-oauth
        {:consumer-key    (:consumer-key env)
         :consumer-secret (:consumer-secret env)
         :sign-in-uri     "/sign-in"
         :callback-uri    "/oauth-callback"
         :finished-uri    "/"})
      (handler/site)
      (session/wrap-session)
      (wrap-request-logging)))

(defn -main [port & args]
  (let [port (Integer/parseInt port)
        env  (load-env "./credentials.edn")]
    (println (str "Starting server on port " port "..."))
    (jetty/run-jetty (make-app env) {:port port})))
