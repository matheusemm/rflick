(ns rflick.core
  (:require [clj-http.client :as client]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.xml :as xml]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [taoensso.timbre :as timbre])
  (:import (java.awt.image BufferedImage)
           (javax.imageio ImageIO)))

(timbre/refer-timbre)

;; HTTP

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

;; Image manipulation

(defn create-image ^BufferedImage
  [^bytes image-bytes]
  (with-open [xin (io/input-stream image-bytes)]
    (ImageIO/read xin)))

(defn resize-image ^BufferedImage
  [^BufferedImage image [width height]]
  (if (and (= width (.getWidth image))
           (= height (.getHeight image)))
    image
    (let [resized (BufferedImage. width height (.getType image))
          gfx (.createGraphics resized)]
      (.drawImage gfx image 0 0 width height nil)
      (.dispose gfx)
      resized)))

(defn save-image
  [^BufferedImage image path]
  (let [extension (subs path (inc (str/last-index-of path ".")))]
    (with-open [xout (io/output-stream path)]
      (ImageIO/write image extension xout))))

;; XML processing

(defn string->stream [s]
  (-> s
      (.getBytes "UTF-8")
      (java.io.ByteArrayInputStream.)))

(defn parse-xml [xml-str]
  (with-open [xin (string->stream xml-str)]
    (xml/parse xin)))

(defn extract-image-urls
  "Extracts images URLs from the given `xml-doc` and returns them as a seq.

  For flickr's public images photo feed, the image URLs are extracted from `<link>`
  elements that have attribute `rel=enclosure`."
  [xml-doc]
  (->> xml-doc
       (xml-seq)
       (filter #(and (= (:tag %) :link)
                     (= (-> % :attrs :rel) "enclosure")))
       (map #(-> % :attrs :href))))

;; Pipeline

(defn retrying
  "Execute `fn` inside a try-catch block at most `max-attempts` times. `retry-fn`
  is a one arg predicate function that determine, based on the `fn` result, if it
  should be retried."
  [fn retry-fn & {:keys [max-attempts] :or {max-attempts 3}}]
  (let [try-fn #(try
                  (debug (str "Attempt #" % "."))
                  (let [r (fn)]
                    (if (retry-fn r)
                      :rflick/retry
                      r))
                  (catch Exception e
                    (warn e (str "Exception on attempt #" % "."))
                    :rflick/retry))]
    (loop [attempt 1
           backoff-ms 500]
      (if (<= attempt max-attempts)
        (let [r (try-fn attempt)]
          (if (= r :rflick/retry)
            (do
              (Thread/sleep backoff-ms)
              (recur (inc attempt) (* 1.5 backoff-ms)))
            r))
        :rflick/retry-error))))

(defn wait-and-close [workers ch]
  (async/go
      (doseq [worker workers]
        (async/<! worker))
      (async/close! ch)))

(defn prepare-download-feed-step [request]
  (info (format "[download-feed] Received request: %s." request))
  (let [feed-chan (async/chan)]
    (async/thread
      (let [response (retrying fetch-feed server-error?)]
        (if (client-error? response)
          (error (format "[download-feed] HTTP error %d when fetching feed %s." (:status response) feed-url))
          (do
            (async/>!! feed-chan (assoc request :feed-content (:body response)))
            (async/close! feed-chan)))))
    feed-chan))

(defn prepare-extract-image-urls-step [feed-chan]
  (let [urls-chan (async/chan 20)]
    (async/go
      (let [{:keys [num-images dimensions feed-content] :as message} (async/<! feed-chan)]
        (info (format "[extract-urls] Received message: %s."
                      (assoc message :feed-content (str (subs feed-content 0 100) "..."))))
        (let [urls (-> feed-content parse-xml extract-image-urls)
              url-messages (take (or num-images (count urls))
                                 (map #(into {} [[:dimensions dimensions]
                                                 [:image-url %]])
                                      urls))]
          (async/onto-chan urls-chan url-messages))))
    urls-chan))

(defn prepare-download-image-step [urls-chan]
  (let [images-chan (async/chan 20)
        workers (vec
                 (repeat
                  4
                  (async/thread
                    (loop [{:keys [image-url] :as message} (async/<!! urls-chan)]
                      (when image-url
                        (info (format "[download-image] Received message: %s." message))
                        (let [response (retrying #(fetch-image image-url) server-error?)]
                          (if (client-error? response)
                            (error (format "[download-image] HTTP error %d when downloading image %s."
                                           (:status response) image-url))
                            (async/>!! images-chan (assoc message :image-bytes (:body response))))
                          (recur (async/<!! urls-chan)))))
                    :done)))]

    (wait-and-close workers images-chan)
    images-chan))

(defn prepare-process-image-step [images-chan]
  (let [resized-chan (async/chan 20)
        workers (vec
                 (repeat
                  4
                  (async/go-loop [{:keys [image-url image-bytes dimensions] :as message} (async/<! images-chan)]
                    (if image-bytes
                      (do
                        (info (format "[process-image] Received message: %s." (-> message
                                                                                  (dissoc :image-bytes)
                                                                                  (assoc :image-size (count image-bytes)))))
                        (let [resized (-> image-bytes
                                          (create-image)
                                          (resize-image dimensions))]
                          (async/>! resized-chan {:image resized
                                                  :image-url image-url})
                          (recur (async/<! images-chan))))
                      :done))))]

    (wait-and-close workers resized-chan)    
    resized-chan))

(defn prepare-save-image-step [resized-chan]
  (dotimes [_ 4]
    (async/thread
      (loop [{:keys [image image-url] :as message} (async/<!! resized-chan)]
        (when image
          (info (format "[save-image] Received message: %s." message))
          (let [file-name (subs image-url (inc (str/last-index-of image-url "/")))]
            (save-image image (str "images/" file-name))
            (info (format "[save-image] Done processing image %s." image-url))
            (recur (async/<!! resized-chan))))))))

;; HTTP server

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
    (-> (prepare-download-feed-step req)
        (prepare-extract-image-urls-step)
        (prepare-download-image-step)
        (prepare-process-image-step)
        (prepare-save-image-step))
    {:status 202
     :headers {}}))

(def app
  (-> handler
      wrap-keyword-params
      wrap-params))

(defn -main []
  (run-jetty app {:port 3000}))
