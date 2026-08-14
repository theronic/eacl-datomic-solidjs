(ns eacl-datahike-demo.eacl-adapter
  "Narrow compatibility bridge for EACL's Datahike tiered-store identity."
  (:require [eacl.backend.v8 :as backend]
            [eacl.client.orchestration :as orchestration]
            [eacl.datahike.backend :as datahike-backend]
            [eacl.datahike.core]))

(defonce ^:private upstream-snapshot-adapter
  datahike-backend/snapshot-adapter)

(defn- stringify-id
  [config]
  (if (contains? config :id)
    (update config :id str)
    config))

(defn portable-snapshot-identity
  "Makes Konserve's required UUID store IDs portable EACL identity data.

  Datahike tiered configs contain the same UUID at the top level and in both
  nested store configs. EACL already stringifies the top-level ID, but the
  nested UUIDs otherwise reach its canonical continuation identity and are
  correctly rejected as non-portable values. Normalize only the public
  snapshot identity: copying the immutable DB value would defeat EACL's
  identity-based exact-generation cache at an unchanged basis."
  [snapshot-id]
  (update-in
   snapshot-id [:database-id :store]
   (fn [store]
     (cond-> (stringify-id store)
       (:frontend-config store) (update :frontend-config stringify-id)
       (:backend-config store) (update :backend-config stringify-id)))))

(defn snapshot-adapter
  [db opts]
  (let [adapter (upstream-snapshot-adapter db opts)
        snapshot-id (backend/operation adapter :snapshot-id)]
    (assoc-in adapter [::backend/operations :snapshot-id]
              #(portable-snapshot-identity (snapshot-id)))))

(defn make-tiered-client
  "Constructs the published EACL Datahike client with a portable tiered-store
  snapshot adapter.  This bridge can be deleted after the upstream adapter
  recursively normalizes nested Konserve store IDs."
  [conn opts]
  (let [api-var (ns-resolve 'eacl.datahike.core 'api)]
    (when-not api-var
      (throw (ex-info "The EACL Datahike client API is unavailable."
                      {:type :eacl-datahike-demo/eacl-api-unavailable})))
    (orchestration/make-client
     (assoc @api-var :snapshot-adapter snapshot-adapter)
     conn
     opts)))
