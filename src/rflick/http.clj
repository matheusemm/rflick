(ns rflick.http
  (:require [clj-http.client :as client]
            [taoensso.timbre :as timbre]))

(timbre/refer-timbre)

(def feed-url "https://www.flickr.com/services/feeds/photos_public.gne")

(defn fetch-feed
  "Simply GET from the flickr public photos feed and returns a map containing
  `:status` and `:body`."
  []
  (let [response (client/get feed-url
                             {:accept :atom+xml
                              :throw-exceptions false})]
    (select-keys response [:status :body])))

(defn fetch-image ^bytes
  [url]
  (let [response (client/get url
                             {:as :byte-array
                              :throw-exceptions false})]
    (select-keys response [:status :body])))

(defn server-error? [response]
  (>= (:status response) 500))

(defn client-error? [response]
  (<= 400 (:status response) 499))
