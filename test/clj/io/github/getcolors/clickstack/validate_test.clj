(ns io.github.getcolors.clickstack.validate-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [green.cli :as green-cli]
            [io.github.getcolors.clickstack.validate :as validate]))

(def fixture-file "test/fixtures/colors.yml")
(def optout-file "test/fixtures/optout.yml")

(defn- read-fixture [path overrides]
  (merge (green-cli/read-state path (str/replace (slurp path) "WORKDIR" ".colors"))
         overrides))
(defn fixture [& {:as overrides}] (read-fixture fixture-file overrides))
(defn optout [& {:as overrides}] (read-fixture optout-file overrides))

(deftest fixture-is-valid (is (= [] (validate/state-errors (fixture)))))

(deftest optout-fixture-is-valid (is (= [] (validate/state-errors (optout)))))

(deftest machine-key-is-not-required
  ;; The standard makes absence meaningful: requiring vultr-ssh-keys would make
  ;; every conforming deployment invalid.
  (is (not-any? #(str/includes? % "vultr-ssh-keys") (validate/state-errors (fixture)))))

(deftest absent-machine-key-selects-keygen
  (is (true? (validate/keygen? (fixture))))
  (is (false? (validate/keygen? (optout)))))

(deftest reports-all-errors
  (let [errors (validate/state-errors
                (fixture :clickstack-host "bad" :clickstack-hyperdx-image "floating"
                         :provider-dns "other" :provider-compute "digitalocean"
                         :vultr-os-id "2284"))]
    (is (<= 5 (count errors)))
    (doseq [part ["host" "image" "provider-dns" "vultr" "os-id"]]
      (is (some #(str/includes? % part) errors) part))))

(deftest accepts-a-digest-pin
  (is (= [] (validate/state-errors
             (fixture :clickstack-caddy-image
                      (str "caddy@sha256:" (apply str (repeat 64 "a"))))))))

(deftest profile-overlay-is-refused
  (is (seq (validate/env-errors {"COLORS_PAR_PROFILE" "other"})))
  (is (nil? (validate/env-errors {}))))

(deftest names-all-package-secrets
  (let [errors (str/join "\n" (validate/secret-errors (fixture)))]
    (doseq [name ["COLORS_PAR_VULTR_API_KEY" "COLORS_PAR_CLOUDFLARE_API_TOKEN"
                  "COLORS_PAR_R2_ACCESS_KEY_ID" "COLORS_PAR_R2_SECRET_ACCESS_KEY"]]
      (is (str/includes? errors name) name))
    ;; The ingestion key is generated on the server, never supplied.
    (is (not (str/includes? errors "INGESTION")))))
