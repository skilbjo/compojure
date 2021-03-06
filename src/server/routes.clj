(ns server.routes
  (:require [buddy.core.hash :as hash]
            [buddy.core.codecs :as codecs]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [compojure.api.sweet :as api]
            [compojure.core :refer [defroutes HEAD GET]]
            [compojure.route :as route]
            [environ.core :refer [env]]
            [jobs.api :as jobs.api]
            [jobs.cljs :as jobs.cljs]
            [jobs.static :as jobs.static]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.anti-forgery :as anti-forgery]
            [ring.middleware.defaults :as ring-defaults]
            [ring.middleware.gzip :as gzip]
            [ring.middleware.json :as ring-json]
            [ring.middleware.session :as session]
            [ring.middleware.cors :as ring-cors]
            [ring.util.response :refer [response]]
            [server.auth :as auth]
            [server.error :as error]
            [server.middleware :as middleware]
            [server.sql :as sql]
            [server.util :as util]
            [spec-tools.spec :as spec])
  (:gen-class))

(defroutes server-routes
  (HEAD "/" [])
  (GET "/index" []
    (jobs.static/index))
  (GET "/routes" []
    (jobs.static/routes))
  (GET "/dashboard" []
    (jobs.static/dashboard)))

(defroutes cljs-routes
  (GET "/" []
    jobs.cljs/app)
  (GET "/cljs" []
    jobs.cljs/app))

(def api-routes
  (api/context "/api/v1" []
    :tags ["api"]
    :coercion :spec

    (api/context "" []
      :tags ["login"]
      (api/OPTIONS "/login" []
        :summary "For CORS to work, need to respond 200 OK and send CORS headers"
        {:status 200})
      (api/context "" []
        ;; TODO does CSRF on /login make sense? How to make it work with swagger?
        ;; per https://github.com/edbond/CSRF - CSRF + ring + POST does not work
        ;; with compojure 1.2.0+ (we are on 1.6.1)
        #_:middleware    #_[anti-forgery/wrap-anti-forgery]
        #_:header-params #_[{x-csrf-token :- :server.spec/authorization nil}]
        (api/POST "/login" []
          :summary "Login, for an authentication token"
          :body-params [user     :- :server.spec/user
                        password :- :server.spec/password]
          (let [user-trusted     (-> user sql/escape util/lower-trim)
                password-trusted (-> password
                                     sql/escape'
                                     hash/sha256
                                     codecs/bytes->hex)]
            (-> {:user     user-trusted
                 :password password-trusted}
                jobs.api/v1.login
                #_response))))) ; do not add reponse here; it will override 401 status (if unauthorized)

    (api/context "/prices/:dataset" [dataset]
      :tags ["prices"]
      :header-params [authorization :- :server.spec/authorization]
      :middleware    [auth/token-auth middleware/authenticated]
      (api/GET "/latest" []
        :summary "Latest prices"
        (let [dataset-trusted (-> dataset sql/escape util/lower-trim)
              response'       (jobs.api/v1.latest dataset-trusted)]
          (-> response'
              response)))

      (api/GET "/" []
        :summary "Price for a specific date"
        :query-params [ticker :- :server.spec/ticker
                       date   :- :server.spec/date]
        (let [dataset-trusted (-> dataset sql/escape util/lower-trim)
              ticker-trusted  (-> ticker sql/escape util/lower-trim)
              date-trusted    (-> date sql/escape' util/lower-trim)
              response'       (jobs.api/v1.quote dataset-trusted
                                                 ticker-trusted
                                                 date-trusted)]
          (-> response'
              response)))

      (api/GET "/today" []
        :summary "Price for today"
        :query-params [ticker :- :server.spec/ticker]
        (let [dataset-trusted (-> dataset sql/escape util/lower-trim)
              ticker-trusted  (-> ticker sql/escape util/lower-trim)
              date-trusted    (-> (util/get-todays-date) sql/escape' util/lower-trim)
              response'       (jobs.api/v1.quote dataset-trusted
                                                 ticker-trusted
                                                 date-trusted)]
          (-> response'
              response))))

    (api/context "/reports" []
      :tags ["reports"]
      (api/OPTIONS "/portfolio" []
        :summary "For CORS to work, need to respond 200 OK and send CORS headers"
        {:status 200})
      (api/OPTIONS "/asset-type" []
        :summary "For CORS to work, need to respond 200 OK and send CORS headers"
        {:status 200})
      (api/OPTIONS "/capitalization" []
        :summary "For CORS to work, need to respond 200 OK and send CORS headers"
        {:status 200})
      (api/OPTIONS "/investment-style" []
        :summary "For CORS to work, need to respond 200 OK and send CORS headers"
        {:status 200})
      (api/OPTIONS "/location" []
        :summary "For CORS to work, need to respond 200 OK and send CORS headers"
        {:status 200})
      (api/context "" []
        :header-params [authorization :- :server.spec/authorization]
        :middleware    [auth/token-auth middleware/authenticated]
        (api/GET "/portfolio" []
          :summary "How's the portfolio doing? (Remember to add 'Token ' + token to the authorization header)"
          :query-params  [user     :- :server.spec/user
                          password :- :server.spec/password]
          (let [user-trusted     (-> user sql/escape util/lower-trim)
                password-trusted (-> password
                                     sql/escape')]
            (-> {:user     user-trusted
                 :password password-trusted}
                jobs.api/v1.portfolio
                response)))

        (api/GET "/asset-type" []
          :summary "How's everything performing by asset type?"
          :query-params  [user     :- :server.spec/user
                          password :- :server.spec/password]
          (let [user-trusted     (-> user sql/escape util/lower-trim)
                password-trusted (-> password
                                     sql/escape')]
            (-> {:user     user-trusted
                 :password password-trusted}
                jobs.api/v1.asset-type
                response)))

        (api/GET "/capitalization" []
          :summary "How's everything performing by capitalization?"
          :query-params  [user     :- :server.spec/user
                          password :- :server.spec/password]
          (let [user-trusted     (-> user sql/escape util/lower-trim)
                password-trusted (-> password
                                     sql/escape')]
            (-> {:user     user-trusted
                 :password password-trusted}
                jobs.api/v1.capitalization
                response)))

        (api/GET "/investment-style" []
          :summary "How's everything performing by investment style?"
          :query-params  [user     :- :server.spec/user
                          password :- :server.spec/password]
          (let [user-trusted     (-> user sql/escape util/lower-trim)
                password-trusted (-> password
                                     sql/escape')]
            (-> {:user     user-trusted
                 :password password-trusted}
                jobs.api/v1.investment-style
                response)))

        (api/GET "/location" []
          :summary "How's everything performing by location?"
          :query-params  [user     :- :server.spec/user
                          password :- :server.spec/password]
          (let [user-trusted     (-> user sql/escape util/lower-trim)
                password-trusted (-> password
                                     sql/escape')]
            (-> {:user     user-trusted
                 :password password-trusted}
                jobs.api/v1.location
                response)))))))

(def swagger
  (-> {:swagger
       {:ui   "/swagger"
        :spec "/swagger.json"
        :middleware [ring-json/wrap-json-response]
        :data {:info {:title       "Aeon API"
                      :description "A webserver in LISP FTW"
                      :version     "1.0.0"}}}}
      (api/api api-routes)))

(defroutes combined-routes
  (-> swagger
      (ring-defaults/wrap-defaults (assoc
                                    ring-defaults/api-defaults
                                    :security
                                    {:anti-forgery false ; for POST to work
                                     :hsts true
                                     :content-type-options :nosniff
                                     :frame-options        :sameorigin
                                     :xss-protection {:enable? true
                                                      :mode    :block}})))

  (-> server-routes
      (ring-defaults/wrap-defaults (assoc
                                    ring-defaults/site-defaults
                                    :security
                                    {:anti-forgery true
                                     :hsts true
                                     :content-type-options :nosniff
                                     :frame-options        :sameorigin
                                     :xss-protection {:enable? true
                                                      :mode    :block}}))
      anti-forgery/wrap-anti-forgery)

  (-> cljs-routes
      (ring-defaults/wrap-defaults (assoc
                                    ring-defaults/site-defaults
                                    :security
                                    {:anti-forgery true
                                     :hsts true
                                     :content-type-options :nosniff
                                     :frame-options        :sameorigin
                                     :xss-protection {:enable? true
                                                      :mode    :block}}))
      anti-forgery/wrap-anti-forgery)

  (route/not-found "<h1>Not Found</h1>"))

(def app ;; ensure: $ unset jdbc_athena_uri when dev/testing
  (-> combined-routes
      (middleware/add-content-security-policy
       :config-path
       "policy/content_security_policy.clj")
      (middleware/wrap-referrer-policy "strict-origin")
      (ring-cors/wrap-cors :access-control-allow-origin [#"https://skilbjo-api.duckdns.org"                ;; how to get this to be any of the netlify previews?
                                                         #"https://thirsty-northcutt-878096.netlify.app/"] ;; look into how to do this... dev build tests on local backend or prod backend? ;; how to tie this up to src/app/events.cljs:19 , where you set backend as skilbjo.duckdns.org ..?
                           :access-control-allow-headers #{"accept"
                                                           "authorization"
                                                           "content-type"
                                                           "origin"}
                           :access-control-allow-methods [:get :post :options])
      (session/wrap-session {:cookie-attrs {:max-age 3600
                                            :secure  true}})
      gzip/wrap-gzip))

(defn -main []  ; java -jar app.jar uses this as the entrypoint
  (log/info "Starting aeon webserver ... ")
  (error/set-default-error-handler)

  ; schedule the healthchecks
  (util/schedule-healthchecks-io)

  ; start the server
  (jetty/run-jetty app
                   {:send-server-version? false
                    :port                 8080
                    :ssl-port             8443
                    :keystore             "/tmp/java_key_store"
                    :key-password         (env :quandl-api-key)
                    :ssl?                 true}))
