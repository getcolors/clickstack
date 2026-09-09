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

(deftest fixtures-valid
  (doseq [f [fixture optout do-fixture do-optout]] (is (= [] (validate/state-errors (f))))))
(deftest invalid-compute-refused
  (doseq [updates [{:provider-compute "unsupported"} {:vultr-plan nil} {:vultr-ssh-sources []} {:vultr-http-sources ["bad"]}]]
    (is (seq (validate/state-errors (merge (fixture) updates))))))
(deftest compute-secrets-deferred
  (let [errors (str/join "\n" (validate/secret-errors (fixture)))]
    (is (str/includes? errors "COLORS_PAR_CLOUDFLARE_API_TOKEN"))
    (is (not (str/includes? errors "COLORS_PAR_VULTR_API_KEY"))))
  (is (= {} (validate/tofu-env (fixture) :provider-compute))))
(deftest application-errors-accumulate
  (let [errors (validate/state-errors (fixture :clickstack-host "bad" :clickstack-admin-email "bad" :clickstack-hyperdx-image "floating" :provider-dns "bad"))]
    (doseq [part ["host" "admin-email" "image" "provider-dns"]] (is (some #(str/includes? % part) errors)))))
