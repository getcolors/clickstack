(ns io.github.getcolors.clickstack.tools-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [io.github.getcolors.clickstack.tools :as tools]
            [io.github.getcolors.clickstack.validate-test :refer [fixture optout]]))

(deftest firewall-sources-parse
  (let [data (tools/infrastructure-data (fixture))]
    (is (= ["0.0.0.0/0" "::/0"] (tools/cidrs data :vultr-http-sources)))))

(deftest infrastructure-data-carries-the-ssh-mode
  (is (true? (:ssh-keygen (tools/infrastructure-data (fixture)))))
  (is (false? (:ssh-keygen (tools/infrastructure-data (optout))))))

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
               "smoke.sh" "inventory.json"]]
      (is (some #(str/ends-with? % f) targets) f))))

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
