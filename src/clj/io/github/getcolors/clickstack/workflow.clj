(ns io.github.getcolors.clickstack.workflow
  (:require [clojure.walk :as walk]
            [green.cli :as green-cli]
            [green.dry-run :as dry-run]
            [green.lifecycle :as lifecycle]
            [green.progress :as progress]
            [green.tofu :as tofu]
            [green.workflow :as wf]
            [io.github.getcolors.clickstack.ssh :as ssh]
            [io.github.getcolors.clickstack.tools :as tools]
            [io.github.getcolors.clickstack.validate :as validate]))

(def defaults {:provider-compute "vultr" :provider-dns "cloudflare"
               :provider-backend "local" :compute-prevent-destroy true
               :workdir ".colors"})

(defn state-output
  "The compute stage's applied `params`, or nil when no state is readable. The
  create matrix keys on this best-effort read: an unreadable state (a fresh
  clone, a missing backend) counts as absent."
  [opts]
  (try (some-> (tofu/outputs (tools/tool-dir opts tools/infrastructure-tool)
                             (tools/backend-credential-env opts))
               :params walk/keywordize-keys)
       (catch Exception _ nil)))

(defn start-step
  ([opts] (start-step opts (System/getenv)))
  ([opts env]
   (lifecycle/preflight
    opts {:defaults defaults :overlay green-cli/read-pars
          :validators
          [(fn [_ env _] (validate/env-errors env))
           (fn [opts _ _] (validate/state-errors opts))
           (fn [opts _ {:keys [event real?]}]
             (when (and real? (contains? #{:create :delete} event))
               (validate/secret-errors opts)))
           (fn [opts _ {:keys [event real?]}]
             (when (and real? (= :delete event) (:compute-prevent-destroy opts))
               [(str "compute destruction is protected; set "
                     (green-cli/par-name :compute-prevent-destroy) "=false to delete")]))]
          :after-validate
          ;; The machine key's create matrix and the Vultr preflight run before
          ;; any template is rendered: an unowned key on disk or at the provider
          ;; stops the run while stopping is still free. Delete fills the same
          ;; template values — a destroy renders before it destroys — but checks
          ;; nothing, because its key cleanup runs after the compute destroy.
          (fn [opts _ {:keys [event real?]}]
            (cond
              (and real? (= :delete event))
              (merge (ssh/with-machine-key opts)
                     (or (state-output opts) {})
                     {:green/exit 0})

              (and real? (= :create event))
              (let [opts (ssh/ensure-key! opts state-output)]
                (if (wf/failed? opts)
                  opts
                  (let [opts (ssh/preflight! (ssh/with-machine-key opts))]
                    (if (wf/failed? opts) opts (assoc opts :green/exit 0)))))

              :else
              (assoc (ssh/with-machine-key opts) :green/exit 0)))} env)))

(defn wire-fn [step run-opts]
  (if (= :delete (:green/event run-opts))
    (case step
      :clickstack/start [start-step :clickstack/ansible]
      :clickstack/ansible [tools/ansible-step :clickstack/dns]
      :clickstack/dns [tools/dns-step :clickstack/infrastructure]
      ;; The local keypair goes last, strictly after a successful compute
      ;; destroy: a failed delete leaves the key, which is still the only
      ;; credential to whatever survived.
      :clickstack/infrastructure [tools/infrastructure-step :clickstack/ssh-cleanup]
      :clickstack/ssh-cleanup [ssh/cleanup-step])
    (case step
      :clickstack/start [start-step :clickstack/infrastructure]
      :clickstack/infrastructure [tools/infrastructure-step :clickstack/dns]
      :clickstack/dns [tools/dns-step :clickstack/ansible]
      :clickstack/ansible [tools/ansible-step :clickstack/acceptance]
      :clickstack/acceptance [tools/acceptance-step])))

(defn backend-advice [tool]
  (tofu/conventional-backend-advice
   {:dir-fn #(tools/tool-dir % tool)
    :key-fn #(str (:profile %) "/" tool ".tfstate")}))

(def side-effecting
  [:clickstack/infrastructure :clickstack/dns :clickstack/ansible
   :clickstack/acceptance :clickstack/ssh-cleanup])

(def workflow
  (-> (wf/workflow {:start :clickstack/start :wire-fn wire-fn})
      (wf/advice-add :clickstack/infrastructure :before ::backend
                     (backend-advice tools/infrastructure-tool))
      (wf/advice-add :clickstack/dns :before ::backend (backend-advice tools/dns-tool))
      progress/advise
      (dry-run/advise side-effecting)))
