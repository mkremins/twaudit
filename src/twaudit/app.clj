(ns twaudit.app
  (:require [clj-time.core :as time]
            [clojure.set :as set]
            [clojure.string :as str]
            [compojure.core :refer [defroutes GET POST]]
            [compojure.handler :as handler]
            [twitter-oauth.middleware]
            [ring.middleware.session :as session]
            [ring.util.response :as response]
            [selmer.parser :as selmer]
            [twitter.api.restful :as twitter]))

;;; configuration

(defonce env ; TODO System/getenv
  (zipmap
    [:consumer-key :consumer-secret]
    (str/split-lines (slurp "./credentials.txt"))))

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

(defn last-active [user]
  (-> user :status :created_at parse-datetime))

(defn profile-url [user]
  (str "https://twitter.com/" (:screen_name user)))

(defn reshape-user [user]
  (assoc user
    :last-active (days-ago (last-active user))
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
                       (map (partial str/join ","))
                       (mapcat (partial get-users creds))
                       (sort-by last-active)
                       (map reshape-user))
                  (catch Exception err
                    (println (str "get-friends: [error] " (.getMessage err)))
                    nil))]
    (println (str "get-friends: loaded " (count friends) " friends!"))
    friends))

(defonce loaded-friends
  (atom {}))

(defn load-friends! [creds screen-name]
  (swap! loaded-friends assoc screen-name
    (future (get-friends creds screen-name))))

(defn maybe-load-friends! [creds screen-name]
  (when (or (not (contains? @loaded-friends screen-name))
            (let [pending (get @loaded-friends screen-name)]
              (and (realized? pending) (nil? @pending))))
    (load-friends! creds screen-name)))

(defn get-loaded-friends [screen-name]
  (when-let [pending (get @loaded-friends screen-name)]
    (when (realized? pending) @pending)))

;;; logging

(defn wrap-request-logging [handler]
  (fn [{method :request-method, uri :uri :as req}]
    (println (str (str/upper-case (name method)) " " uri))
    (handler req)))

;;; routes

(defn main-route [{:keys [session] :as req}]
  (if-let [{:keys [screen-name oauth-creds]} (:twitter session)]
    (if-let [friends (get-loaded-friends screen-name)]
      (selmer/render-file "index.html" {:screen_name screen-name :friends friends})
      (do (maybe-load-friends! oauth-creds screen-name)
          (str "Hello, @" screen-name "! We're still loading your friends – check back soon.")))
    (str "You're not signed in to Twitter! <a href=\"/sign-in\">Click here to sign in.</a>")))

(defroutes app-routes
  (GET "/"             req (main-route req))
  (GET "/favicon.ico"  []  (response/not-found "404")))

(defonce app
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
