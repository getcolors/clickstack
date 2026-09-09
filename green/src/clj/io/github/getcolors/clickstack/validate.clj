(ns io.github.getcolors.clickstack.validate
  (:require [clojure.string :as str]
            [green.cli :as green-cli]
            [io.github.getcolors.clickstack.compute :as compute]
            [io.github.getcolors.compute-ssh :as ssh]
            [io.github.getcolors.once.validate :as once-validate]))

(def profile-par (green-cli/par-name :profile))

(def default-compute-provider "vultr")

(def required
  "Every key desired state must carry whichever provider is selected. The
  provider-scoped keys come from `compute-providers`."
  [:profile :workdir :provider-compute :provider-dns :provider-backend
   :compute-prevent-destroy :clickstack-host :clickstack-admin-email
   :clickstack-hyperdx-image :clickstack-otel-collector-image
   :clickstack-clickhouse-image :clickstack-mongo-image :clickstack-caddy-image
])

(def image-keys
  [:clickstack-hyperdx-image :clickstack-otel-collector-image
   :clickstack-clickhouse-image :clickstack-mongo-image :clickstack-caddy-image])

(def host-re #"^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$")
(def email-re #"^[^@\s]+@[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$")
(def image-re #"^[^\s:@]+(?:/[^\s:@]+)*(?::[^\s:@]+|@sha256:[0-9a-f]{64})$")

(defn missing? [x] (or (nil? x) (and (string? x) (str/blank? x))))

(defn keygen? [opts] (try (= "managed" (:mode (ssh/mode opts))) (catch Exception _ true)))

(defn env-errors [env]
  (when (not-empty (str (get env profile-par)))
    [(str profile-par " is set; profile must come from colors.yml only")]))

(defn state-errors
  "Every problem with desired state at once: the missing keys (this package's
  and the selected provider's), the package's own checks, then the Compute
  Provider Standard's — selection, the network contract and the provider
  rules — which are ONCE's over `spec`."
  [opts]
  (vec
   (concat
    (for [k required
          :when (missing? (get opts k))]
      (str k " is required"))
    (when-not (= "cloudflare" (:provider-dns opts))
      [":provider-dns must be cloudflare"])
    (when-not (contains? #{"s3" "r2"} (:provider-backend opts))
      [":provider-backend must be s3 or r2"])
    (when-not (boolean? (:compute-prevent-destroy opts))
      [":compute-prevent-destroy must be true or false"])
    (when-not (or (missing? (:clickstack-host opts))
                  (re-matches host-re (str (:clickstack-host opts))))
      [":clickstack-host must be a fully qualified hostname"])
    (when-not (or (missing? (:clickstack-admin-email opts))
                  (re-matches email-re (str (:clickstack-admin-email opts))))
      [":clickstack-admin-email must be an email address"])
    (for [k image-keys
          :let [v (get opts k)]
          :when (and (not (missing? v)) (not (re-matches image-re (str v))))]
      (str k " must carry an explicit image tag or digest"))
    (compute/errors opts))))

(defn backend-secrets [opts]
  (:secrets (get-in once-validate/providers
                    [:provider-backend (:provider-backend opts)])))

(defn secret-errors
  "Credentials a real create or delete needs: the selected compute provider's,
  Cloudflare's, and the backend's. The HyperDX ingestion key is not here: it is
  generated on the server and never supplied by the operator."
  [opts]
  (let [keys (concat []
                     [:cloudflare-api-token]
                     (backend-secrets opts))]
    (for [k (distinct keys) :when (missing? (get opts k))]
      (str "required credential is not set: " (green-cli/par-name k)))))

(defn tofu-env [opts slot]
  (case slot
    :provider-dns {:cloudflare-api-token "CLOUDFLARE_API_TOKEN"}
    :provider-backend (:tofu-env (get-in once-validate/providers
                                         [:provider-backend (:provider-backend opts)]) {})
    {}))
