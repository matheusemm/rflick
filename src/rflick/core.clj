(ns rflick.core
  (:require [rflick.pipeline :as p]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [taoensso.timbre :as timbre]))

(timbre/refer-timbre)

(defn parse-num-images [request]
  (let [n (some-> (get-in request [:params :num-images])
                  Integer/parseInt)]
    (when (and n (<= 1 n 20))
      n)))

(defn parse-dimensions [request]
  [(Integer/parseInt (get-in request [:params :width]))
   (Integer/parseInt (get-in request [:params :height]))])

(defn handler
  "Handler that builds and executes the image processing pipeline for each request
  received. The final result is having resized image files saved in the ./images
  directory.

  Supported request (query) parameters:
  * :num-images: (optional) Number of images to process, must be 1 <= num-images <= 20.
                 If not provided or outside the valid range, defaults to all images.
  * :width: (required) Width of the resized images.
  * :height: (required) Height of the resized images."
  [request]
  (debug (format "[handler] Received request: %s." request))
  (let [req {:num-images (parse-num-images request)
             :dimensions (parse-dimensions request)}]
    (p/run-pipeline req)
    {:status 202
     :headers {}}))

(def app
  (-> handler
      wrap-keyword-params
      wrap-params))

(defn create-images-dir []
  (clojure.java.io/make-parents "images/fake-file.txt"))

(defn -main []
  (create-images-dir)
  (run-jetty app {:port 3000}))
