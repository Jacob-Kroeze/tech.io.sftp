(ns tech.v3.io.sftp-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [tech.v3.io.sftp]
            [tech.v3.io :as t.io]
            [tech.config.core :as config]))

;; todo set up tests from config
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
  (let [{:keys [test-sftp-host
                test-sftp-username
                test-sftp-private-key-path
                tech-sftp-test-secret-url]}
        (config/get-config-map)
        host test-sftp-host
        username test-sftp-username
        secret-url tech-sftp-test-secret-url
        _ (config/set-config! :my-private-key
          (slurp (str (config/get-config :user-home)
                   test-sftp-private-key-path)))]
    ;; reset provider class
    (require '[tech.v3.io.sftp] :reload)
    (is (nil?
          (t.io/copy
            (io/input-stream (.getBytes "a,b,c\n1,2,3"))
            (format "sftp://%s/%s/outgoing/.done/test2.csv" host username))))
    (is (nil?
          (t.io/copy
            (format "sftp://%s/%s/outgoing/.done/test2.csv" host username)
            (io/output-stream "xyz"))))))

(comment
  (config/reload-config!)
  )