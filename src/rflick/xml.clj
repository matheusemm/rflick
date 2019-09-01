(ns rflick.xml
  (:require [clojure.xml :as xml]))

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
