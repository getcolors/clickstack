(ns io.github.getcolors.clickstack.tools
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [green.ansible :as ansible]
            [green.cli :as green-cli]
            [green.process :as process]
            [green.scaffold :as sc]
            [green.tofu :as tofu]
            [green.workflow :as wf]
            [io.github.getcolors.once.utils :as once-utils]
            [io.github.getcolors.clickstack.ssh :as ssh]
            [io.github.getcolors.clickstack.validate :as validate]))

(def infrastructure-tool "clickstack-infrastructure")
(def dns-tool "clickstack-dns")
(def ansible-tool "clickstack-ansible")
(def root "io.github.getcolors.clickstack.tools")
(def template-opts sc/preserve-jinja-delimiters)

(defn tool-dir [opts tool] (green-cli/stage-dir opts tool {:default-profile "clickstack"}))
(defn template [path file] (keyword (str root "." path) file))
(defn spec [source target data] {:template source :target target :data data :opts template-opts})
(defn raw-spec [target content] (sc/content-spec target content))

(defn cidrs [opts k]
  (let [v (get opts k) xs (if (sequential? v) v (str/split (str v) #"[,\s]+"))]
    (->> xs (map (comp str/trim str)) (remove str/blank?) vec)))

(defn credential-env [opts & slots]
  (not-empty
   (into {} (keep (fn [[k env-var]]
                    (when-let [v (not-empty (str (get opts k)))] [env-var v])))
         (apply merge (map #(validate/tofu-env opts %) (conj (vec slots) :provider-backend))))))
(defn backend-credential-env [opts] (credential-env opts))

(defn fallback-params [opts]
  {:ip "192.0.2.10" :user "root" :sudoer "root" :name (:profile opts)})
(defn output-params [result]
  (some-> (get-in result [:tofu/outputs :params]) walk/keywordize-keys))

;; ---------------------------------------------------------------- compute

(defn infrastructure-data [opts]
  (assoc opts
         :ssh-keygen (validate/keygen? opts)
         :ssh-sources-hcl (tofu/hcl-list (cidrs opts :vultr-ssh-sources))
         :http-sources-hcl (tofu/hcl-list (cidrs opts :vultr-http-sources))))

(defn infrastructure-step [opts]
  (let [dir (tool-dir opts infrastructure-tool)
        specs [(spec (template "infrastructure" "main.tf") (str dir "/main.tf")
                     (infrastructure-data opts))]
        result (tofu/tofu-with-spec opts specs
                                    {:dir dir :env (credential-env opts :provider-compute)})]
    (cond
      (wf/failed? result) result
      (= :build (:green/event opts)) (merge result (fallback-params opts))
      (= :delete (:green/event opts)) result
      :else (merge result (fallback-params opts) (output-params result)))))

;; -------------------------------------------------------------------- dns

(defn zone
  "The Cloudflare zone the UI host belongs to (its registrable domain)."
  [opts]
  (once-utils/registrable-domain (:clickstack-host opts)))

(defn dns-json [opts]
  (tofu/constructs-json
   [(tofu/construct :resource :cloudflare_dns_record :clickstack
                    {:zone_id "${data.cloudflare_zone.zone.id}"
                     :name (:clickstack-host opts) :content (:ip opts) :type "A"
                     :proxied true :ttl 1})]))

(defn dns-step [opts]
  (let [dir (tool-dir opts dns-tool)
        data (assoc opts
                    :ip (or (:ip opts) (:ip (fallback-params opts)))
                    :clickstack-zone (zone opts))
        specs [(spec (template "dns" "main.tf") (str dir "/main.tf") data)
               (raw-spec (str dir "/record.tf.json") (dns-json data))]]
    (tofu/tofu-with-spec opts specs {:dir dir :env (credential-env opts :provider-dns)})))

;; ---------------------------------------------------------------- ansible

(defn inventory [opts]
  (json/generate-string
   {:all {:children {:clickstack {:hosts {(:profile opts)
                                          {:ansible_host (or (:ip opts) "192.0.2.10")
                                           :ansible_user "root"}}}}}}
   {:pretty true}))

(defn ansible-data
  "Template values for the Ansible stage. `ssh-private-key-path` reaches
  ansible.cfg so convergence uses the deployment's own key in keygen mode,
  where nothing guarantees an agent holds it."
  [opts]
  (assoc opts
         :ip (or (:ip opts) "192.0.2.10")
         :ssh-keygen (validate/keygen? opts)))

(defn ansible-specs [opts]
  (let [dir (tool-dir opts ansible-tool) data (ansible-data opts)]
    [(spec (template "ansible" "ansible.cfg") (str dir "/ansible.cfg") data)
     (spec (template "ansible" "main.yml") (str dir "/main.yml") data)
     (spec (template "ansible" "cleanup.yml") (str dir "/cleanup.yml") data)
     (spec (template "ansible" "compose.yml") (str dir "/compose.yml") data)
     (spec (template "ansible" "Caddyfile") (str dir "/Caddyfile") data)
     (spec (template "ansible" "setup.sh") (str dir "/setup.sh") data)
     (spec (template "ansible" "smoke.sh") (str dir "/smoke.sh") data)
     (raw-spec (str dir "/inventory.json") (inventory data))]))

(defn ansible-step [opts]
  (let [dir (tool-dir opts ansible-tool)]
    (ansible/ansible-with-spec opts
      {:dir dir :inventory "inventory.json"
       :playbooks {:create "main.yml" :delete "cleanup.yml"}
       :host-key-checking false}
      (ansible-specs opts))))

;; ------------------------------------------------------------- acceptance

(defn wait-for
  "True once `args` exits zero, retrying every five seconds."
  [args attempts]
  (loop [n attempts]
    (let [r (process/run-with-timeout args {} 20000)]
      (cond (zero? (:exit r)) true
            (pos? n) (do (Thread/sleep 5000) (recur (dec n)))
            :else false))))

(defn http-status
  "The status code a request returns, as a string, or \"000\" when the request
  never completed."
  [args]
  (str/trim (str (:out (process/run-with-timeout args {} 20000)))))

(def endpoint-wired?
  "Statuses that prove Caddy routed to the collector rather than swallowing the
  request. A rejected or malformed payload is still proof of a live receiver;
  404 and the 5xx family are not."
  #{"200" "400" "401" "403" "415" "422"})

(defn acceptance-step
  "Public health checks after a real create. The end-to-end ingest proof runs
  on the server, inside the playbook, where the generated ingestion key lives;
  what is checked from here is what a user can actually reach: the UI over
  HTTPS and the OTLP receiver behind it."
  [opts]
  (if (not= :create (:green/event opts))
    (assoc opts :green/exit 0)
    (let [base (str "https://" (:clickstack-host opts))]
      (cond
        (not (wait-for ["curl" "-fsS" "-o" "/dev/null" (str base "/")] 60))
        (assoc opts :green/exit 1 :green/err "HyperDX UI did not become reachable over HTTPS")

        :else
        (let [page (process/run-with-timeout ["curl" "-fsS" (str base "/")] {} 20000)
              otlp (http-status
                    ["curl" "-s" "-o" "/dev/null" "-w" "%{http_code}"
                     "-X" "POST" "-H" "content-type: application/json"
                     "--data" "{\"resourceLogs\":[]}" (str base "/v1/logs")])]
          (cond
            (not (re-find #"(?i)hyperdx" (str (:out page))))
            (assoc opts :green/exit 1 :green/err "the HyperDX UI did not render")

            (not (contains? endpoint-wired? otlp))
            (assoc opts :green/exit 1
                   :green/err (str "the public OTLP endpoint is not wired: /v1/logs returned " otlp))

            :else
            (assoc opts :green/exit 0
                   :clickstack/acceptance {:ui "ok" :otlp-status otlp})))))))
