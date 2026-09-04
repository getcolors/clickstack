(ns io.github.getcolors.clickstack.validate-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [green.cli :as green-cli]
            [io.github.getcolors.clickstack.validate :as validate]))

(def fixture-file "test/fixtures/colors.yml")
(def optout-file "test/fixtures/optout.yml")
(def do-fixture-file "test/fixtures/colors-digitalocean.yml")
(def do-optout-file "test/fixtures/optout-digitalocean.yml")

(defn- read-fixture [path overrides]
  (merge (green-cli/read-state path (str/replace (slurp path) "WORKDIR" ".colors"))
         overrides))
(defn fixture [& {:as overrides}] (read-fixture fixture-file overrides))
(defn optout [& {:as overrides}] (read-fixture optout-file overrides))
(defn do-fixture [& {:as overrides}] (read-fixture do-fixture-file overrides))
(defn do-optout [& {:as overrides}] (read-fixture do-optout-file overrides))

(deftest fixture-is-valid (is (= [] (validate/state-errors (fixture)))))

(deftest optout-fixture-is-valid (is (= [] (validate/state-errors (optout)))))

(deftest digitalocean-fixtures-are-valid
  (is (= [] (validate/state-errors (do-fixture))))
  (is (= [] (validate/state-errors (do-optout)))))

;; --- the spec handed to ONCE

(deftest the-spec-carries-this-packages-registry-sources-and-default
  ;; The operations are ONCE's; this is the data they run over. A colour
  ;; whose registry, sources or default drifts fails here, in that colour.
  (is (= #{"digitalocean" "vultr"} (set (keys (:registry validate/spec)))))
  (is (= validate/compute-providers (:registry validate/spec)))
  (is (= {:required [:digitalocean-region :digitalocean-size :digitalocean-image
                     :digitalocean-ssh-sources :digitalocean-http-sources]
          :secrets [:do-token]
          :tofu-env {:do-token "DIGITALOCEAN_TOKEN"}}
         (get-in validate/spec [:registry "digitalocean"])))
  (is (= {:required [:vultr-region :vultr-plan :vultr-os-id
                     :vultr-ssh-sources :vultr-http-sources]
          :secrets [:vultr-api-key]
          :tofu-env {:vultr-api-key "VULTR_API_KEY"}}
         (get-in validate/spec [:registry "vultr"])))
  (is (= {:non-empty ["ssh-sources"] :may-be-empty ["http-sources"]} (:sources validate/spec)))
  (is (= "vultr" (:default validate/spec)))
  (is (= validate/default-compute-provider (:default validate/spec)))
  (is (not (contains? validate/spec :name-rules)) "the name rules are ONCE's"))

;; --- the compute-provider registry

(deftest unsupported-provider-names-the-advertised-ones
  (is (some #{":provider-compute must be one of digitalocean, vultr"}
            (validate/state-errors (fixture :provider-compute "hetzner")))))

(deftest required-keys-follow-the-selected-provider
  (is (some #{":digitalocean-size is required"}
            (validate/state-errors (do-fixture :digitalocean-size nil))))
  (is (some #{":vultr-plan is required"}
            (validate/state-errors (fixture :vultr-plan nil))))
  ;; The other provider's keys are neither required nor refused, so one
  ;; colors.yml can carry both and move between providers by one edit.
  (is (not-any? #(str/includes? % "vultr") (validate/state-errors (do-fixture))))
  (is (= [] (validate/state-errors (fixture :digitalocean-region "ams3"
                                            :digitalocean-size "s-1vcpu-1gb"))))
  (is (= [] (validate/state-errors (do-fixture :vultr-os-id "not-checked-here")))))

(deftest name-and-machine-key-are-never-required
  ;; Compute Name Standard: the profile is the default. SSH Keypair Standard:
  ;; absence selects keygen mode.
  (doseq [errors [(validate/state-errors (fixture :vultr-name nil))
                  (validate/state-errors (do-fixture))]]
    (is (not-any? #(str/includes? % "-name") errors))
    (is (not-any? #(str/includes? % "-ssh-keys") errors))))

;; --- the compute name

(deftest compute-name-falls-back-to-the-profile
  (is (= "clickstack-digitalocean-fixture" (validate/compute-name (do-fixture))))
  (is (= "clickstack-digitalocean-optout" (validate/compute-name (do-optout))))
  (is (= "clickstack-fixture" (validate/compute-name (fixture :vultr-name nil))))
  (is (= "clickstack-fixture" (validate/compute-name (fixture :vultr-name ""))))
  (is (= "clickstack-fixture" (validate/compute-name (fixture :vultr-name "REPLACE_ME"))))
  (is (= "custom-label" (validate/compute-name (fixture :vultr-name "custom-label"))))
  ;; The override is read from the selected provider's key alone.
  (is (= "clickstack-digitalocean-fixture"
         (validate/compute-name (do-fixture :vultr-name "custom-label")))))

(deftest compute-key-is-provider-scoped
  (is (= :vultr-ssh-sources (validate/compute-key (fixture) "ssh-sources")))
  (is (= :digitalocean-http-sources (validate/compute-key (do-fixture) "http-sources"))))

;; --- the network contract

(deftest ssh-sources-must-not-be-empty
  (is (some #{":vultr-ssh-sources must list at least one CIDR"}
            (validate/state-errors (fixture :vultr-ssh-sources []))))
  (is (some #{":digitalocean-ssh-sources must list at least one CIDR"}
            (validate/state-errors (do-fixture :digitalocean-ssh-sources " , "))))
  ;; No public HTTP is a legitimate deployment.
  (is (= [] (validate/state-errors (fixture :vultr-http-sources []))))
  (is (= [] (validate/state-errors (do-fixture :digitalocean-http-sources [])))))

(deftest malformed-sources-are-refused-before-any-provider-call
  (is (some #{":vultr-http-sources entry \"10.0.0.0\" is not an IPv4 or IPv6 CIDR"}
            (validate/state-errors (fixture :vultr-http-sources ["0.0.0.0/0" "10.0.0.0"]))))
  (is (some #{":digitalocean-ssh-sources entry \"office.example.com/32\" is not an IPv4 or IPv6 CIDR"}
            (validate/state-errors (do-fixture :digitalocean-ssh-sources "office.example.com/32"))))
  ;; Only the selected provider's lists are checked.
  (is (= [] (validate/state-errors (do-fixture :vultr-ssh-sources ["garbage"])))))

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
                         :clickstack-admin-email "not-an-email"
                         :provider-dns "other" :provider-compute "hetzner"))]
    (is (<= 5 (count errors)))
    (doseq [part ["host" "image" "admin-email" "provider-dns" "provider-compute"]]
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
    (is (not (str/includes? errors "INGESTION")))
    (is (not (str/includes? errors "COLORS_PAR_DO_TOKEN")))))

(deftest secrets-and-tofu-env-follow-the-selected-provider
  (let [errors (str/join "\n" (validate/secret-errors (do-fixture)))]
    (is (str/includes? errors "COLORS_PAR_DO_TOKEN"))
    (is (str/includes? errors "COLORS_PAR_CLOUDFLARE_API_TOKEN"))
    (is (not (str/includes? errors "COLORS_PAR_VULTR_API_KEY"))))
  (is (= {:do-token "DIGITALOCEAN_TOKEN"} (validate/tofu-env (do-fixture) :provider-compute)))
  (is (= {:vultr-api-key "VULTR_API_KEY"} (validate/tofu-env (fixture) :provider-compute)))
  (is (= {} (validate/tofu-env (fixture :provider-compute "hetzner") :provider-compute))))
