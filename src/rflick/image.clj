(ns rflick.image
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.awt.image BufferedImage)
           (javax.imageio ImageIO)))

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
