(ns tech.v3.io.sftp-test
  (:require [clojure.test :refer :all]
            [tech.v3.io.sftp]
            [tech.v3.io :as t.io]
            [tech.config.core :as config]))


;; t.io/copy from sftp server to file
(deftest username-and-password
  (let [host "example.com"
        username "wd-fam"
        password ""
        secret-url (str "sftp://"
                     "example.com?username="
                     username
                     "&password="
                     password)
        _ (config/set-config! :tech-sftp-test-user-name-and-password secret-url)]
    ;; reset provider class
    (require '[tech.v3.io.sftp] :reload)
    (is (nil? (t.io/copy
                "/tmp/clj-test/test.csv"
                (format "sftp://%s/outgoing/.done/test.csv" host))))
    (is (nil? (t.io/copy
                (format "sftp://%s/outgoing/.done/test.csv" host)
                "/tmp/clj-test/test.csv")))))


(deftest private-key
  (let [host "example.com"
        username "freddy"
        private-key-env-lookup "id_rsa"
        secret-url (str "sftp://"
                     host
                     "?username="
                     username
                     "&private-key-env="
                     private-key-env-lookup)
        _ (config/set-config! :tech-sftp-test-private-key secret-url)
        _ (config/set-config! :my-private-key (slurp (str (config/get-config :user-home) "/.ssh/id_rsa")))
        ]
    ;; reset provider class
    (require '[tech.v3.io.sftp] :reload)
    (is (nil? (t.io/copy
                "/tmp/clj-test/test.csv"
                (format "sftp://%s/path/test2.csv" host))))
    (is (nil? (t.io/copy
                (format "sftp://%s/path/test2.csv" host)
                "/tmp/clj-test/test.csv"
                )))))


(deftest azure-vault)
config/*config-map*



