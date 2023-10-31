(ns tech.v3.io.azure-vault
  (:require [tech.config.core :as config])
  (:import (com.azure.identity DefaultAzureCredentialBuilder)
           (com.azure.security.keyvault.secrets SecretClientBuilder
                                                SecretClient)))


(def client
  (let [vault-name (config/get-config :azure-key-vault-name)
        vault-url (str "https://" vault-name ".vault.azure.net")
        credential (-> (new DefaultAzureCredentialBuilder) (.build))
        client (-> (new SecretClientBuilder)
                 (.vaultUrl vault-url)
                 (.credential credential)
                 (.buildClient))]
    client))



(defn get-secret [^SecretClient client key-name]
  {key-name
   (.getValue (.getSecret client key-name))})


(defn read-credentials [& [filter-fn]]
  (->> (.listPropertiesOfSecrets client)
    (.iterator)
    iterator-seq
    (mapv #(-> % .getName))
    (filter (or filter-fn identity))
    (mapv #(->> % (get-secret client)))
    (into {})
    ))



#_ (def client (-> (vault/new-client (config/get-config :vault-addr))
              (vault/authenticate! :token
                (slurp (str (System/getProperty "user.home")
                         "/.vault-token")))))

(comment
  (def t' (read-credentials client))
  (into {} t')
  (read-credentials client #(-> % (.contains "tech-sftp")))

  (def key' (get-secret client "tech-sftp-wd-transfer-server"))

  (.getValue key')
  )