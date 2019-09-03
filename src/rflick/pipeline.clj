(ns rflick.pipeline
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [rflick.http :as http]
            [rflick.image :as img]
            [rflick.xml :as xml]
            [taoensso.timbre :as timbre]))

(timbre/refer-timbre)

(defn retrying
  "Execute `fn` inside a try-catch block at most `max-attempts` times. `retry-fn`
  is a one arg predicate function that determine, based on the `fn` result, if it
  should be retried."
  ([proc fn retry-fn] (retrying proc fn retry-fn 3))
  ([proc fn retry-fn max-attempts]
   (let [try-fn #(try
                  (debug (format "[%s] Attempt #%d." proc %))
                  (let [r (fn)]
                    (if (retry-fn r)
                      :rflick/retry
                      r))
                  (catch Exception e
                    (warn e (format "[%s] Exception on attempt #%d." proc %))
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
        :rflick/retry-error)))))

(defn run-pipeline [req]
  (let [feed-chan (async/chan)
        urls-chan (async/chan 20)
        downloads-chan (async/chan 20)
        resized-chan (async/chan 20)]
    ;; Step 1: download feed and publish content to feed-chan
    (async/thread
      (info "[download-feed] Received request: %s." req)
      (let [response (retrying "fetch-feed" http/fetch-feed http/server-error?)]
        (if (http/client-error? response)
          (error (format "[fetch-feed] HTTP error %d." (:status response)))
          (async/>!! feed-chan (assoc req :feed-content (:body response)))))
      (async/close! feed-chan))

    ;; Step 2: extract image URLs from feed content and publish to urls-chan
    (async/pipeline-async
     1
     urls-chan
     (fn [{:keys [num-images dimensions feed-content] :as msg} ch]
       (async/go
         (info (format "[extract-urls] Received message: %s."
                       (-> msg (dissoc :feed-content) (assoc :feed-size (count feed-content)))))
         (let [urls (-> feed-content xml/parse-xml xml/extract-image-urls)
               out-msgs (take (or num-images (count urls))
                              (map #(hash-map :dimensions dimensions
                                              :image-url %)
                                   urls))]
           (async/onto-chan ch out-msgs))))
     feed-chan)

    ;; Step 3: download images and publish to downloads-chan
    (async/pipeline-async
     4
     downloads-chan
     (fn [{:keys [image-url] :as msg} ch]
       (async/thread
         (info "[download-image] Received message: %s." msg)
         (let [response (retrying "download-image" #(http/fetch-image image-url) http/server-error?)]
           (if (http/client-error? response)
             (error (format "[download-image] HTTP error %d when downloading image %s." (:status response) image-url))
             (async/>!! ch (assoc msg :image-bytes (:body response)))))
         (async/close! ch)))
     urls-chan)

    ;; Step 4: resize images and publish to resized-chan
    (async/pipeline-async
     4
     resized-chan
     (fn [{:keys [image-url image-bytes dimensions] :as msg} ch]
       (async/go
         (info (format "[resize-image] Received message: %s."
                       (-> msg (dissoc :image-bytes) (assoc :image-size (count image-bytes)))))
         (let [resized (-> image-bytes
                           (img/create-image)
                           (img/resize-image dimensions))]
           (async/>! ch {:image resized
                         :image-url image-url})
           (async/close! ch))))
     downloads-chan)

    ;; Step 5: save images
    (dotimes [_ 4]
      (async/thread
        (loop [{:keys [image image-url] :as msg} (async/<!! resized-chan)]
          (when msg
            (info (format "[save-image] Received message: %s." msg))
            (let [file (subs image-url (inc (str/last-index-of image-url "/")))]
              (img/save-image image (str "images/" file))
              (info (format "[save-image] Done processing image %s." image-url))
              (recur (async/<!! resized-chan)))))))))
