(ns io.github.getcolors.clickstack.validate
  (:require [clojure.string :as str]
            [green.cli :as green-cli]
            [io.github.getcolors.once.ssh :as once-ssh]
            [io.github.getcolors.once.validate :as once-validate]))

(def profile-par (green-cli/par-name :profile))

(def required
  "Every key desired state must carry. `vultr-ssh-keys` is deliberately absent:
  per the SSH Keypair Standard its *absence* selects keygen mode, where the
  package owns the keypair, and requiring it would make conforming deployments
  invalid."
  [:profile :workdir :provider-compute :provider-dns :provider-backend
   :compute-prevent-destroy :clickstack-host
   :clickstack-hyperdx-image :clickstack-otel-collector-image
   :clickstack-clickhouse-image :clickstack-mongo-image :clickstack-caddy-image
   :vultr-name :vultr-region :vultr-plan :vultr-os-id
   :vultr-ssh-sources :vultr-http-sources
   :r2-bucket :r2-endpoint])

(def image-keys
  [:clickstack-hyperdx-image :clickstack-otel-collector-image
   :clickstack-clickhouse-image :clickstack-mongo-image :clickstack-caddy-image])

(def host-re #"^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$")
(def image-re #"^[^\s:@]+(?:/[^\s:@]+)*(?::[^\s:@]+|@sha256:[0-9a-f]{64})$")

(defn missing? [x] (or (nil? x) (and (string? x) (str/blank? x))))

(defn keygen?
  "Whether this deployment owns its machine keypair. Delegates to ONCE, the
  standard's reference implementation, so one rule decides it everywhere."
  [opts]
  (once-ssh/keygen? opts))

(defn env-errors [env]
  (when (not-empty (str (get env profile-par)))
    [(str profile-par " is set; profile must come from colors.yml only")]))

(defn state-errors [opts]
  (vec
   (concat
    (for [k required :when (missing? (get opts k))] (str k " is required"))
    (when-not (= "vultr" (:provider-compute opts))
      [":provider-compute must be vultr"])
    (when-not (= "cloudflare" (:provider-dns opts))
      [":provider-dns must be cloudflare"])
    (when-not (contains? #{"local" "s3" "r2"} (:provider-backend opts))
      [":provider-backend must be local, s3, or r2"])
    (when-not (boolean? (:compute-prevent-destroy opts))
      [":compute-prevent-destroy must be true or false"])
    (when-not (or (missing? (:clickstack-host opts))
                  (re-matches host-re (str (:clickstack-host opts))))
      [":clickstack-host must be a fully qualified hostname"])
    (for [k image-keys
          :let [v (get opts k)]
          :when (and (not (missing? v)) (not (re-matches image-re (str v))))]
      (str k " must carry an explicit image tag or digest"))
    (when-not (or (missing? (:vultr-os-id opts)) (integer? (:vultr-os-id opts)))
      [":vultr-os-id must be Vultr's numeric operating-system id"]))))

(defn backend-secrets [opts]
  (:secrets (get-in once-validate/providers
                    [:provider-backend (:provider-backend opts)])))

(defn secret-errors
  "Credentials a real create or delete needs. The HyperDX ingestion key is not
  here: it is generated on the server and never supplied by the operator."
  [opts]
  (let [keys (concat [:vultr-api-key :cloudflare-api-token] (backend-secrets opts))]
    (for [k (distinct keys) :when (missing? (get opts k))]
      (str "required credential is not set: " (green-cli/par-name k)))))

(defn tofu-env [opts slot]
  (case slot
    :provider-compute {:vultr-api-key "VULTR_API_KEY"}
    :provider-dns {:cloudflare-api-token "CLOUDFLARE_API_TOKEN"}
    :provider-backend (:tofu-env (get-in once-validate/providers
                                         [:provider-backend (:provider-backend opts)]) {})
    {}))
