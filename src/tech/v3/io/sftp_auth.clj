(ns tech.v3.io.sftp-auth
  (:require [tech.config.core :as config]
            [tech.v3.io.auth :as t.io.auth]
            [tech.v3.io.url :as url]
            [io.pedestal.log :as log]))


(defn add-ns-creds
  "make namespace by e.g <user>@<host-server>/key"
  [simple-m host]
  (update-keys
    simple-m
    #(keyword host (str %)))
  )

(defn from-url
  "Parse url into namespaced username and password credentials map"
  [s]
  (let [{:keys [path arguments]} (url/url->parts s)
        [host _] path]
    (-> arguments
      (->> (into {}))
      (add-ns-creds host))))


(defn env-read-cred-fn
  "Look for any tech-sftp | TECH_SFTP prefixed env
  If multiple users on one server, then prepend username
  example: <user>@<host>.
  Credentials can remain one map with unique keys and Sftp client will parse this way of formatting url."
  []
  (->> (config/get-config-map)
    (filter
      #(-> % first name
         (.contains "tech-sftp")))
    (map (fn [[k v]]
           (if (url/url? v)
             (from-url v)
             (update-keys {k v} keyword))))
    (into {})))


(def azure-read-cred-fn
  (delay (try
           (locking #'t.io.auth/authenticated-provider
             (requiring-resolve
               'tech.v3.io.azure-vault/read-credentials))
           (catch Throwable e e))))

(defn format-azure-creds
  "Get vault keys prefixed with tech-sftp
  and all keys in tech-sftp-vault-keys config"
  []
  (let [az-cred-fn @azure-read-cred-fn
        az-creds (fn [] (az-cred-fn #(some-> % (.contains "tech-sftp"))))]
    (if (instance? Throwable az-cred-fn)
      (log/warn :azure-vault "Failed to load azure-vault" :exception az-cred-fn)
      (->> (az-creds)
        (map (fn [[k v]]
               (if (url/url? v)
                 (from-url v)
                 (update-keys {k v} keyword))))
        (into {})))))


(defn env-and-azure-creds
  "Return all env creds prefixed with tech-sftp
  return all azure-vault creds if
  :azure-key-vault-name is set in configuration or <resource>.edn"
  []
  (let [env-creds (env-read-cred-fn)
        az-creds (format-azure-creds)]
    (merge env-creds az-creds)))


(defn provided-keys
  "Dump keys of all the credentials we've found
  Insecure?"
  []
  (keys (env-and-azure-creds)))


(defn provider []
  (t.io.auth/auth-provider env-and-azure-creds {:provided-auth-keys (provided-keys)}))


(comment

  (def test-provider (t.io.auth/auth-provider env-and-azure-creds {:provided-auth-keys (provided-keys)}))

  (tech.v3.io.protocols/authenticate test-provider {} {})
  )

