(ns io.github.getcolors.clickstack.tools
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [green.ansible :as ansible]
            [green.cli :as green-cli]
            [green.process :as process]
            [green.scaffold :as sc]
            [green.tofu :as tofu]
            [green.workflow :as wf]
            [io.github.getcolors.once.compute :as compute]
            [io.github.getcolors.once.utils :as once-utils]
            [io.github.getcolors.clickstack.ssh-config :as ssh-config]
            [io.github.getcolors.clickstack.validate :as validate]))

(def infrastructure-tool "clickstack-infrastructure")
(def dns-tool "clickstack-dns")
(def ansible-tool "clickstack-ansible")
(def ansible-local-tool "clickstack-ansible-local")
(def root "io.github.getcolors.clickstack.tools")
(def template-opts sc/preserve-jinja-delimiters)

(defn tool-dir [opts tool] (green-cli/stage-dir opts tool {:default-profile "clickstack"}))
(defn template [path file] (keyword (str root "." path) file))
(defn spec [source target data] {:template source :target target :data data :opts template-opts})
(defn raw-spec [target content] (sc/content-spec target content))

(def cidrs
  "The source lists as validate parses them, so the template and the
  validator can never disagree about what an entry is."
  validate/cidrs)

(defn credential-env [opts & slots]
  (not-empty
   (into {} (keep (fn [[k env-var]]
                    (when-let [v (not-empty (str (get opts k)))] [env-var v])))
         (apply merge (map #(validate/tofu-env opts %) (conj (vec slots) :provider-backend))))))
(defn backend-credential-env [opts] (credential-env opts))

(def fallback-params
  "What `build` and `--dry-run` render in place of a compute output: the
  documentation address, shaped like the selected provider's real `params` so
  every later stage sees the same keys either way. ONCE's."
  compute/fallback-params)

(def resolved-compute
  "Refuse to hand 192.0.2.10 to Ansible on a real converge whose compute
  output carries no `ip`. ONCE's; `infrastructure-step` is what wires it."
  compute/resolved-compute)

;; ---------------------------------------------------------------- compute

(defn infrastructure-data
  "Template values for the compute stage. The name and the source lists are
  resolved here once, so a template interpolates values and never branches on
  which provider it belongs to."
  [opts]
  (assoc opts
         :ssh-keygen (validate/keygen? opts)
         :compute-name (validate/compute-name opts)
         :ssh-sources-hcl (tofu/hcl-list (cidrs opts (validate/compute-key opts "ssh-sources")))
         :http-sources-hcl (tofu/hcl-list (cidrs opts (validate/compute-key opts "http-sources")))))

(defn infrastructure-template
  "Providers are selected by template directory, `infrastructure/<provider>/`,
  not by conditionals inside one file; the rendered target is the same
  `main.tf` whichever directory it came from."
  [opts]
  (template (str "infrastructure." (:provider-compute opts)) "main.tf"))

(defn infrastructure-step [opts]
  (let [dir (tool-dir opts infrastructure-tool)
        specs [(spec (infrastructure-template opts) (str dir "/main.tf")
                     (infrastructure-data opts))]
        result (tofu/tofu-with-spec opts specs
                                    {:dir dir :env (credential-env opts :provider-compute)})]
    (cond
      (wf/failed? result) result
      (= :build (:green/event opts)) (merge result (fallback-params opts))
      (= :delete (:green/event opts)) result
      :else (resolved-compute result (fallback-params opts) (compute/output-params result)))))

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

;; ---------------------------------------------------------- ansible (local)

(defn ansible-local-data
  "Only what a `build` genuinely knows. The address, the user and the alias are
  run-time facts and reach the play as extra-vars instead, so the rendered
  playbook carries no IP and is identical on every workstation (SSH Config
  Standard §6)."
  [opts]
  (assoc opts
         :ssh-keygen (validate/keygen? opts)
         :ssh-config-identity-file (ssh-config/identity-file opts)))

(defn ansible-local-specs [opts]
  (let [dir (tool-dir opts ansible-local-tool) data (ansible-local-data opts)]
    [(spec (template "ansible-local" "ansible.cfg") (str dir "/ansible.cfg") data)
     (spec (template "ansible-local" "inventory.ini") (str dir "/inventory.ini") data)
     (spec (template "ansible-local" "main.yml") (str dir "/main.yml") data)]))

(defn ansible-local-step
  "Write or remove the `~/.ssh/config` block. The same playbook serves both
  events; `block_state` is what distinguishes them."
  [opts]
  (let [dir (tool-dir opts ansible-local-tool)
        delete? (= :delete (:green/event opts))]
    (ansible/ansible-with-spec opts
      {:dir dir :inventory "inventory.ini"
       :playbooks {:create "main.yml" :delete "main.yml"}
       :extra-vars {:host_alias (ssh-config/host-alias opts)
                    :ip (or (:ip opts) (:ip (fallback-params opts)))
                    :user (or (:user opts) "root")
                    :block_state (if delete? "absent" "present")}}
      (ansible-local-specs opts))))

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
    (if (and (= :delete (:green/event opts)) (not (:ip opts)))
      ;; No compute in state: there is no host to clean up, and the rendered
      ;; inventory would fall back to 192.0.2.10. Remove the rendered tree the
      ;; way a completed cleanup would and let the teardown continue.
      (assoc (sc/scaffold opts (ansible-specs opts))
             :green/exit 0 :clickstack/cleanup :skipped-no-compute)
      (ansible/ansible-with-spec opts
        {:dir dir :inventory "inventory.json"
         :playbooks {:create "main.yml" :delete "cleanup.yml"}
         :host-key-checking false}
        (ansible-specs opts)))))

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
