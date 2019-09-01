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
      (let [response (retrying http/fetch-feed http/server-error?)]
        (if (http/client-error? response)
          (error (format "[download-feed] HTTP error %d when fetching feed %s." (:status response) http/feed-url))
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
        (let [urls (-> feed-content xml/parse-xml xml/extract-image-urls)
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
                        (let [response (retrying #(http/fetch-image image-url) http/server-error?)]
                          (if (http/client-error? response)
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
                                          (img/create-image)
                                          (img/resize-image dimensions))]
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
            (img/save-image image (str "images/" file-name))
            (info (format "[save-image] Done processing image %s." image-url))
            (recur (async/<!! resized-chan))))))))
