(ns io.github.getcolors.clickstack.tools-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [io.github.getcolors.clickstack.tools :as tools]
            [io.github.getcolors.clickstack.validate-test :refer [fixture optout do-fixture do-optout]]))

(deftest firewall-sources-parse
  (let [data (tools/infrastructure-data (fixture))]
    (is (= ["0.0.0.0/0" "::/0"] (tools/cidrs data :vultr-http-sources)))))

(deftest infrastructure-data-carries-the-ssh-mode
  (is (true? (:ssh-keygen (tools/infrastructure-data (fixture)))))
  (is (false? (:ssh-keygen (tools/infrastructure-data (optout)))))
  (is (true? (:ssh-keygen (tools/infrastructure-data (do-fixture)))))
  (is (false? (:ssh-keygen (tools/infrastructure-data (do-optout))))))

(deftest infrastructure-data-reads-the-selected-providers-keys
  ;; The template interpolates one resolved name and one resolved list per
  ;; port, whichever provider they came from.
  (let [data (tools/infrastructure-data (do-fixture :digitalocean-ssh-sources ["10.0.0.0/8"]
                                                    :vultr-ssh-sources ["192.0.2.0/24"]))]
    (is (= "[\"10.0.0.0/8\"]" (:ssh-sources-hcl data)))
    (is (= "clickstack-digitalocean-fixture" (:compute-name data))))
  (is (= "clickstack-fixture" (:compute-name (tools/infrastructure-data (fixture))))))

(deftest template-directory-follows-the-provider
  (is (= :io.github.getcolors.clickstack.tools.infrastructure.vultr/main.tf
         (tools/infrastructure-template (fixture))))
  (is (= :io.github.getcolors.clickstack.tools.infrastructure.digitalocean/main.tf
         (tools/infrastructure-template (do-fixture))))
  ;; A registry entry without a template directory would pass every unit test
  ;; and fail the first build.
  (doseq [provider ["vultr" "digitalocean"]]
    (is (io/resource (str "io/github/getcolors/clickstack/tools/infrastructure/" provider "/main.tf"))
        provider)))

(deftest fallback-params-are-shaped-per-provider
  (is (= {:provider "vultr" :ip "192.0.2.10" :user "root" :sudoer "root" :name "clickstack-fixture"}
         (tools/fallback-params (fixture))))
  (is (= {:provider "digitalocean" :ip "192.0.2.10" :user "root" :sudoer "root"
          :name "clickstack-digitalocean-fixture"}
         (tools/fallback-params (do-fixture)))))

(deftest a-real-create-refuses-a-missing-ip-output
  ;; 192.0.2.10 is the documentation address build renders with; a real
  ;; converge must never fall back to it.
  (let [refused (tools/resolved-compute {} (tools/fallback-params (fixture)) nil)]
    (is (= 1 (:green/exit refused)))
    (is (str/includes? (:green/err refused) "compute produced no ip output")))
  (let [refused (tools/resolved-compute {} (tools/fallback-params (fixture)) {:name "x"})]
    (is (= 1 (:green/exit refused))))
  (let [ok (tools/resolved-compute {} (tools/fallback-params (fixture))
                                   {:ip "203.0.113.9" :provider "vultr"})]
    (is (nil? (:green/exit ok)))
    (is (= "203.0.113.9" (:ip ok)))))

(deftest delete-without-compute-skips-the-remote-cleanup
  ;; No address in state means no host to clean up, and the inventory would
  ;; otherwise fall back to 192.0.2.10.
  (let [work (str (fs/create-temp-dir {:prefix "clickstack-tools"}))]
    (try
      (let [r (tools/ansible-step (assoc (fixture) :workdir work :green/event :delete))]
        (is (= 0 (:green/exit r)))
        (is (= :skipped-no-compute (:clickstack/cleanup r))))
      (finally (fs/delete-tree work)))))

(deftest dns-zone-is-registrable-domain
  (is (= "example.com" (tools/zone (fixture)))))

(deftest dns-record-is-host-and-proxied
  (let [json (tools/dns-json (assoc (fixture) :ip "192.0.2.10"))]
    (is (str/includes? json "clickstack.example.com"))
    (is (str/includes? json "192.0.2.10"))
    (is (str/includes? json "proxied"))))

(deftest inventory-keeps-one-target
  (let [inventory (tools/inventory (assoc (fixture) :ip "192.0.2.10"))]
    (is (str/includes? inventory "192.0.2.10"))
    (is (str/includes? inventory "clickstack-fixture"))))

(deftest ansible-renders-the-whole-stack
  (let [targets (map #(str (:target %)) (tools/ansible-specs (fixture)))]
    (doseq [f ["ansible.cfg" "main.yml" "cleanup.yml" "compose.yml" "Caddyfile"
               "setup.sh" "smoke.sh" "inventory.json"]]
      (is (some #(str/ends-with? % f) targets) f))))

(deftest setup-carries-the-admin-email-and-no-password
  ;; The team is created during convergence, so the login identity is rendered;
  ;; its password is generated on the server and must never reach a rendered
  ;; file.
  (let [specs (tools/ansible-specs (fixture))
        play (some #(when (str/ends-with? (str (:target %)) "main.yml") %) specs)
        rendered (str (:data play))]
    (is (str/includes? rendered "admin@clickstack.example.com"))
    (is (not (str/includes? rendered "HYPERDX_ADMIN_PASSWORD=Cs-")))))

(deftest acceptance-is-skipped-outside-a-real-create
  (doseq [event [:build :delete]]
    (is (= 0 (:green/exit (tools/acceptance-step (assoc (fixture) :green/event event)))))))

(deftest a-wired-otlp-endpoint-tolerates-a-rejected-payload
  ;; A live receiver that refuses an anonymous or malformed request is proof
  ;; the route exists; 404 and the 5xx family are not.
  (is (contains? tools/endpoint-wired? "401"))
  (is (contains? tools/endpoint-wired? "400"))
  (is (not (contains? tools/endpoint-wired? "404")))
  (is (not (contains? tools/endpoint-wired? "502")))
  (is (not (contains? tools/endpoint-wired? "000"))))
