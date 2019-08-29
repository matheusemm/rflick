(ns rflick.core
  (:require [clj-http.client :as client]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.xml :as xml])
  (:import (java.awt.image BufferedImage)
           (javax.imageio ImageIO)))

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

(defn fetch-as-bytes ^bytes
  [url]
  (let [response (client/get url
                             {:as :byte-array
                              :throw-exceptions false})]
    (select-keys response [:status :body])))

;; Image manipulation

(defn bytes-to-image ^BufferedImage
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
