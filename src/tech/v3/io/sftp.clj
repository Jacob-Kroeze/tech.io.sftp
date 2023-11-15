(ns tech.v3.io.sftp
  (:require [clojure.string :as string]
            [io.pedestal.log :as log]
            [tech.v3.io :as t.io]
            [tech.v3.io.sftp-auth :as sftp-auth]
            [tech.v3.io.sftp-client :as sftp]
            [tech.v3.io.auth :as io-auth]
            [tech.v3.io.url :as url]
            [tech.v3.io.protocols :as io-prot]
            [clojure.java.io :as io])
  (:import (java.io File InputStream OutputStream)))


;;;;;;;;
;;; tech io protocol
;;;
;;;;

(set! *warn-on-reflection* true)


(defn- is-byte-array?
  [v]
  (instance? (Class/forName "[B") v))


(defn url-parts->path
  [{:keys [path]}]
  (string/join "/" (rest path)))


(defn url-parts->host
  [{:keys [path]}]
  (first path))


(defn get-object [host path options]
  (sftp/call-sftp-cmd {:cmd :get :path path} (merge {:host host} options)))


(defn list-objects
  "recursive not implemented"
  [host path options]
  (sftp/call-sftp-cmd {:cmd :ls :path path} (merge {:host host} options)))


(defn delete-object! [host path options]
  (sftp/call-sftp-cmd  {:cmd :rm :path path} (merge {:host host} options)))


(defn get-object-metadata
  "get stat from first result of :ls"
  [host path options]
  (sftp/call-sftp-cmd
    {:cmd :get-metadata
     :path path}
    (merge {:host host} options)))


(defn put-object!
  "When v argument is present, put onto sftp server otherwise
  return an output-stream. Verifies content length is the same on server"
  ([host path options]
   (sftp/call-sftp-cmd {:cmd :output-stream :path path}
                       (merge {:host host} options)))
  ([host path v options]
   (with-open [^OutputStream out (put-object! host path options)
               ^InputStream in (t.io/input-stream v)]
     (let [src-byte-len (cond
                          (is-byte-array? v)
                          (count v)
                          (instance? File v)
                          (.length ^File v)
                          :else nil)]
       (io/copy in out)
       (let [dest-byte-len (-> (get-object-metadata host path options)
                               :byte-length)]
         (when-not (= src-byte-len dest-byte-len)
           (throw (ex-info "Source-Local byte-length does not match Destin-Server byt-length"
                           {:host host
                            :path path
                            :src-byte-len src-byte-len
                            :dest-byte-len dest-byte-len}))))))))


(defrecord SftpProvider [default-options]
  io-prot/IOProvider
  (input-stream [provider url-parts options]
    (get-object
      (url-parts->host url-parts)
      (url-parts->path url-parts)
      (merge default-options options)))

  (output-stream! [provider url-parts options]
    ;; JSch provides an output stream/ pipe
    ;;Write the output stream on close
    (put-object!
      (url-parts->host url-parts)
      (url-parts->path url-parts)
      options))

  (exists? [provider url-parts options]
    (try
      (io-prot/metadata provider url-parts (merge default-options options))
      true
      (catch Throwable e
        false)))

  (ls [provider url-parts options]
    (let [host (url-parts->host url-parts)
          ^String path (url-parts->path url-parts)]
      (->> (list-objects
             (url-parts->host url-parts)
             (url-parts->path url-parts)
             (merge default-options options))
        )))

  (delete! [provider url-parts options]
    (delete-object! (url-parts->host url-parts)
      (url-parts->path url-parts)
      (merge default-options options)))

  (metadata [provider url-parts [options]]
    (log/info :metadata-url-parts url-parts :options options :provider provider)
    (get-object-metadata (url-parts->host url-parts)
                         (url-parts->path url-parts)
                         (merge default-options options)))

  io-prot/ICopyObject
  (get-object [provider url-parts options]
    (io-prot/input-stream provider url-parts options))

  (put-object! [provider url-parts value options]
    (put-object! (url-parts->host url-parts)
      (url-parts->path url-parts)
      value
      (merge default-options options))))


(defn sftp-provider
  [default-options]
  (->SftpProvider default-options))


(defn create-default-sftp-provider
  "If there are auth config wrap with auth provider"
  []
  (io-auth/authenticated-provider
    (sftp-provider {})
    (sftp-auth/provider)))


(def ^:dynamic default-s3-provider* (delay (create-default-sftp-provider)))


(defmethod io-prot/url-parts->provider :sftp
  [& args]
  ;; late bind to allow vault set up
  @default-s3-provider*)


(comment

  (def url "sftp://test.test")
  (def url2 "sftp://test2.test")
  (url/url->parts url)
  (t.io/copy
    url
    "/tmp/test.json")

  (t.io/copy
    "/tmp/test.json"
    url)

  (t.io/copy
    url
    url2)

  )