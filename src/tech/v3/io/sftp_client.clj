(ns tech.v3.io.sftp-client
  (:require [clj-ssh.ssh :as ssh]
            [clojure.string :as string]
            [io.pedestal.log :as log]
            [tech.config.core :as config]
            [clojure.java.io :as io])
  (:import (clj_ssh.ssh KeyPairIdentity)
           (com.jcraft.jsch ChannelSftp$LsEntry HostKeyRepository JSch SftpATTRS
                            SftpProgressMonitor Session ChannelSftp
                            HostKey)))

(set! *warn-on-reflection* true)

(deftype
  DisconnectMonitor [^Session session ^ChannelSftp channel]
  SftpProgressMonitor
  (init [_ a b c d]
    ;(println "SftpProgressMonitor init...")
    ;(println "a: " a "b: " b "c: " c "d: " d)
    )
  (count [_ n]
    ;(log/info :sftp-content-byte-length n)
    true)
  (end [this]
    (let [a-future
          ;; monitor called after e.g. InputStream close
          ;; but before _sendClose on the channel (not sure what this does).
          ;; so wait a moment then disconnect all
          (future
            (Thread/sleep 3000)
            (do
              (log/info :sftp "..disconnecting channel and session" :sftp-host (.getHost session))
              (ssh/disconnect-channel channel)
              (ssh/disconnect session)
              (log/info :sftp "done disconnecting")))]
      (log/info :will-disconnect-future a-future))
    (log/info :channel-sftp-input-stream-end "end"))
  )

(defn ->metadata [^SftpATTRS attrs host path & [ls-entry]]
  {:byte-length (.getSize attrs)
   :url         (str "sftp://" host "/" path
                  (when ls-entry
                    (str "/"
                      (.getFilename ^ChannelSftp$LsEntry ls-entry))))
   :directory?  (.isDir attrs)})

(defn get-creds
  "Get creds given host.
  Useful in call-sftp-cmd, the cred map passed in from t.io protocol will have shape
  {ns-key-host/key-name val} {ns-key2-host/key-name val} so output for
  the client must be filtered for this host {key-name val}"
  [m host]
  (->> m
    (filter
      #(= host
         (namespace (first %))))
    (map #(conj [] (keyword (string/replace
                              (name (first %))
                              ":" ""))
            (second %)))
    (into {})))

(defn decode-base-64 [^String s]
  (.decode (java.util.Base64/getDecoder)
    s))

(defn host-key [{:keys [host key-string]}]
  (HostKey. host (decode-base-64 key-string)))

(defn add-to-repo
  "Add parsed host key string to JSch known_hosts_repository. User-info nil by default"
  [[host key-type key-string] ^HostKeyRepository repo & [user-info]]
  (log/info :add-host-key-to-jsch-repo "try add" :host host)
  (let [hkey (host-key {:host host :key-string key-string})
        hkey-bytes (decode-base-64 key-string)]
    (when (= HostKeyRepository/NOT_INCLUDED
            (.check repo host hkey-bytes))
      (.add repo hkey
        user-info))))

(defn config-known-hosts!
  "Return count added. jsch Agent set to retrieve known_hosts from file and builds host key repo.
   We also look in opts and config for :tech-sftp-known-hosts and add to jsc agent host key repo
   If known hosts file isn't created an in-memory object is used as the repo; see JSch"
  [^JSch agent opts]
  (let [^String known-hosts-path (or (:tech-sftp-known-hosts-file opts)
                                   (str (System/getProperty "user.home") "/.ssh/known_hosts"))
        known-hosts-file (io/file known-hosts-path)
        _ (when (.exists known-hosts-file) (.setKnownHosts agent known-hosts-path))
        repo (.getHostKeyRepository agent)
        from-opts (some->
                    (:tech-sftp-known-hosts opts)
                    string/split-lines)
        user-info nil]
    (->> from-opts
      (remove nil?)
      (mapv
        #(-> %
           (string/split #" ")
           (add-to-repo repo)
           )))

    (mapv #(into {} [[:host (.getHost ^HostKey %)]
                     [:fingerprint (.getFingerPrint ^HostKey % agent)]])
      (.getHostKey repo))))

;;/maybe locking in known hosts file weird
(comment
  (config/reload-config!)
  (def keys' (config-known-hosts!
               (JSch.)
               options'))

  (:tech-sftp-known-hosts options')
  (:tech-sftp-known-hosts-file options')
  )

(defn call-sftp-cmd
  "sftp-cmd
  :cmd of :output-stream, :ls, :rm
  :path on-server
  options (see sftp-auth/cred-fn)
  :host-ns/username :host-ns/password
  :private-key-env lookup-in-env-key (not namespaced)
  Note: if nothing writes to output-stream, a zero byte file remains at that :path on the server"
  [sftp-cmd {:keys [host] :as options}]
  (let [args (->> [(:input sftp-cmd) (:path sftp-cmd)] (remove nil?))
        {:keys [username password private-key-env]} (-> options (get-creds host))
        private-key-key (keyword private-key-env)
        [host-user host-server] (let [v (string/split host #"@" 2)]
                                  (if (= 2 (count v))
                                    v
                                    (into [nil] v)))
        options (merge options
                  {:username username :password password})
        _ (def options' options)
        agent (JSch.)
        _ (config-known-hosts! agent
            options)
        _ (when private-key-key
            (.addIdentity agent
              (KeyPairIdentity. agent ""
                (ssh/keypair
                  agent
                  {:private-key
                   (or (private-key-key options)
                     (config/get-config
                       private-key-key))}))
              nil                                           ;; empty passphrase
              ))
        ^Session session (ssh/session agent host-server options)
        ^ChannelSftp channel (ssh/sftp-channel (if (ssh/connected? session)
                                    session
                                    (do (ssh/connect session)
                                        session)))
        _ (if (ssh/connected-channel? channel)
            channel
            (ssh/connect-channel channel))]
    (condp = (:cmd sftp-cmd)
           :output-stream (.put channel
                            ^String (:path sftp-cmd)
                            ^SftpProgressMonitor (DisconnectMonitor. session channel)
                            0)
           :get (.get channel
                  (:path sftp-cmd))
      :ls (->> (.ls channel
                 (:path sftp-cmd))
                 (map (fn [^ChannelSftp$LsEntry ls-entry]
                        (-> (.getAttrs ls-entry)
                          (->metadata host (:path sftp-cmd) ls-entry)))))
      :get-metadata (-> (.stat channel (:path sftp-cmd))
                      (->metadata host (:path sftp-cmd)))
      :rm (.rm channel
            (:path sftp-cmd))
      ;; else
      (throw (IllegalArgumentException. (str (:cmd sftp-cmd) " is not an sftp cmd or not implemented"))))))




(comment
  (def opts {:host/username "test"
                :host/password "test"
                :host "test.test"})

  ;; convertable to io/output-stream
  (call-sftp-cmd
    {:cmd  :output-stream
     :path "test.csv"}
    opts
    )


  (call-sftp-cmd
    {:cmd  :ls
     :path "/uploads"}
    opts)

  (call-sftp-cmd
    {:cmd  :get-metadata
     :path "test.csv"}
    opts)


  (call-sftp-cmd
    {:cmd  :rm
     :path "test.csv"}
    opts
    )
  )
