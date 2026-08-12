(ns eacl-datahike-demo.data
  "Standalone demo schema, fixtures, metadata reads, and append-only seeding."
  (:require [datahike.api :as d]
            [eacl.core :as eacl]
            [eacl.datahike.schema :as schema]))

(def page-size-options [10 20 50 100 250 500 1000])
(def default-page-size 20)

(def default-schema
  "definition user {}

definition platform {
  relation super_admin: user
  permission view = super_admin
}

definition account {
  relation owner: user
  relation platform: platform

  permission admin = owner + platform->super_admin
  permission view = admin
}

definition team {
  relation account: account
  relation leader: user

  permission admin = account->admin + leader
  permission view = admin
}

definition vpc {
  relation account: account
  relation shared_admin: user

  permission admin = account->admin + shared_admin
  permission view = admin
}

definition server {
  relation account: account
  relation team: team
  relation vpc: vpc
  relation shared_admin: user

  permission admin = account->admin + shared_admin
  permission view = admin + account->view + team->view + vpc->view + shared_admin
}")

(def recursive-schema
  "definition user {}

definition platform {
  relation super_admin: user
  permission view = super_admin
}

definition account {
  relation owner: user
  relation platform: platform
  relation parent: account

  permission admin = owner + parent->admin + platform->super_admin
  permission view = admin + parent->admin
}

definition team {
  relation account: account
  relation leader: user

  permission admin = account->admin + leader
  permission view = admin
}

definition vpc {
  relation account: account
  relation shared_admin: user

  permission admin = account->admin + shared_admin
  permission view = admin
}

definition server {
  relation account: account
  relation team: team
  relation vpc: vpc
  relation shared_admin: user
  relation parent: server

  permission admin = account->admin + shared_admin
  permission view = admin + parent->view + account->view + team->view + vpc->view + shared_admin
}")

(def schema-presets
  [{:id "default" :label "Non-recursive" :schema default-schema}
   {:id "recursive" :label "Recursive" :schema recursive-schema}])

(def quick-subjects
  [{:id "super-user" :label "Super user"}
   {:id "user-1" :label "User 1"}
   {:id "user-2" :label "User 2"}])

(def demo-attributes
  [{:db/ident :demo/type
    :db/doc "EACL Datahike demo object type."
    :db/cardinality :db.cardinality/one
    :db/valueType :db.type/keyword
    :db/index true}
   {:db/ident :demo/name
    :db/doc "Human-readable demo object name."
    :db/cardinality :db.cardinality/one
    :db/valueType :db.type/string
    :db/index true}
   {:db/ident :server/name
    :db/doc "Human-readable server name used by benchmark counts."
    :db/cardinality :db.cardinality/one
    :db/valueType :db.type/string
    :db/index true}])

(def demo-profile
  {:accounts 4
   :teams-per-account 2
   :vpcs-per-account 1
   :servers-per-account 12})

(def interactive-profile
  {:teams-per-account 4
   :vpcs-per-account 2
   :servers-per-account 2000})

(def default-seed-transaction-size 250)
(def default-seed-pause-ms 50)

(def ready-progress
  {:status :ready
   :servers-added 0
   :servers-completed 0
   :servers-target 0
   :total-servers 0
   :label nil
   :error nil})

(defn ->object
  [type id]
  (eacl/spice-object type id))

(defn count-servers
  [db]
  (d/q '[:find (count ?server) .
         :where [?server :server/name]]
       db))

(defn- parse-account-number
  [account-id]
  (when-let [[_ digits] (re-matches #"account-(\d+)" (str account-id))]
    (Long/parseLong ^String digits)))

(defn- next-account-number
  [db]
  (->> (d/q '[:find [?account-id ...]
               :where
               [?account :demo/type :account]
               [?account :eacl/id ?account-id]]
             db)
       (keep parse-account-number)
       (reduce max -1)
       inc))

(defn- account-batch
  [account-number {:keys [teams-per-account vpcs-per-account
                          servers-per-account]}]
  (let [account-id (str "account-" account-number)
        account-tempid (d/tempid :db.part/user)
        owner-tempid (d/tempid :db.part/user)
        owner-id (str account-id "-owner")
        team-data
        (mapv
         (fn [team-number]
           (let [team-tempid (d/tempid :db.part/user)
                 leader-tempid (d/tempid :db.part/user)
                 team-id (str account-id "-team-" team-number)]
             {:tempid team-tempid
              :entities
              [{:db/id team-tempid
                :eacl/id team-id
                :demo/type :team
                :demo/name (str "Team " (inc team-number) " · " account-id)}
               {:db/id leader-tempid
                :eacl/id (str team-id "-leader")
                :demo/type :user
                :demo/name (str "Leader " (inc team-number) " · " account-id)}]
              :relationships
              [(eacl/->Relationship
                (->object :account account-id)
                :account
                (->object :team team-id))
               (eacl/->Relationship
                (->object :user (str team-id "-leader"))
                :leader
                (->object :team team-id))]}))
         (range teams-per-account))
        vpc-data
        (mapv
         (fn [vpc-number]
           (let [vpc-tempid (d/tempid :db.part/user)
                 admin-tempid (d/tempid :db.part/user)
                 vpc-id (str account-id "-vpc-" vpc-number)]
             {:tempid vpc-tempid
              :entities
              [{:db/id vpc-tempid
                :eacl/id vpc-id
                :demo/type :vpc
                :demo/name (str "VPC " (inc vpc-number) " · " account-id)}
               {:db/id admin-tempid
                :eacl/id (str vpc-id "-admin")
                :demo/type :user
                :demo/name (str "VPC admin " (inc vpc-number) " · " account-id)}]
              :relationships
              [(eacl/->Relationship
                (->object :account account-id)
                :account
                (->object :vpc vpc-id))
               (eacl/->Relationship
                (->object :user (str vpc-id "-admin"))
                :shared_admin
                (->object :vpc vpc-id))]}))
         (range vpcs-per-account))
        team-ids (mapv #(get-in % [:entities 0 :eacl/id]) team-data)
        vpc-ids (mapv #(get-in % [:entities 0 :eacl/id]) vpc-data)
        server-data
        (mapv
         (fn [server-number]
           (let [server-tempid (d/tempid :db.part/user)
                 server-id (str account-id "-server-" server-number)]
             {:entities
              [{:db/id server-tempid
                :eacl/id server-id
                :demo/type :server
                :demo/name (str "Server " (inc server-number) " · " account-id)
                :server/name (str "Server " (inc server-number))}]
              :relationships
              [(eacl/->Relationship
                (->object :account account-id)
                :account
                (->object :server server-id))
               (eacl/->Relationship
                (->object :team
                          (nth team-ids
                               (mod server-number teams-per-account)))
                :team
                (->object :server server-id))
               (eacl/->Relationship
                (->object :vpc
                          (nth vpc-ids
                               (mod server-number vpcs-per-account)))
                :vpc
                (->object :server server-id))]}))
         (range servers-per-account))
        entities
        (concat
         [{:db/id account-tempid
           :eacl/id account-id
           :demo/type :account
           :demo/name (str "Account " (inc account-number))}
          {:db/id owner-tempid
           :eacl/id owner-id
           :demo/type :user
           :demo/name (str "Owner · " account-id)}]
         (mapcat :entities team-data)
         (mapcat :entities vpc-data)
         (mapcat :entities server-data))
        relationships
        (concat
         [(eacl/->Relationship
           (->object :platform "platform")
           :platform
           (->object :account account-id))
          (eacl/->Relationship
           (->object :user owner-id)
           :owner
           (->object :account account-id))]
         (mapcat :relationships team-data)
         (mapcat :relationships vpc-data)
         (mapcat :relationships server-data))]
    {:account-id account-id
     :entities (vec entities)
     :relationships (vec relationships)}))

(defn- write-relationships!
  [acl relationships]
  (when (seq relationships)
    (eacl/write-relationships!
     acl
     (mapv #(eacl/->RelationshipUpdate :touch %) relationships))))

(defn- paced!
  [pause-ms]
  (when (pos? pause-ms)
    (Thread/sleep pause-ms)))

(defn- transact-entity-chunks!
  [conn entities transaction-size pause-ms]
  (doseq [chunk (partition-all transaction-size entities)]
    (d/transact conn (vec chunk))
    (paced! pause-ms)))

(defn- write-relationship-chunks!
  [acl relationships transaction-size pause-ms]
  (doseq [chunk (partition-all transaction-size relationships)]
    (write-relationships! acl chunk)
    (paced! pause-ms)))

(defn- install-root-fixtures!
  [conn acl]
  (d/transact
   conn
   [{:db/id (d/tempid :db.part/user)
      :db/ident :test/platform
      :eacl/id "platform"
      :demo/type :platform
      :demo/name "Platform"}
     {:db/id (d/tempid :db.part/user)
      :db/ident :user/super-user
      :eacl/id "super-user"
      :demo/type :user
      :demo/name "Super user"}
     {:db/id (d/tempid :db.part/user)
      :db/ident :test/user1
      :eacl/id "user-1"
      :demo/type :user
      :demo/name "User 1"}
     {:db/id (d/tempid :db.part/user)
      :db/ident :test/user2
      :eacl/id "user-2"
      :demo/type :user
      :demo/name "User 2"}])
  (eacl/write-relationship!
   acl
   {:operation :touch
    :subject (->object :user "super-user")
    :relation :super_admin
    :resource (->object :platform "platform")}))

(defn- install-accounts!
  [conn acl profile account-start progress!]
  (mapv
   (fn [offset]
     (let [account-number (+ account-start offset)
           {:keys [account-id entities relationships]}
           (account-batch account-number profile)]
       (d/transact conn entities)
       (write-relationships! acl relationships)
       (progress! account-id (inc offset))
       account-id))
   (range (:accounts profile))))

(defn- entid
  [db ident-or-ref]
  (:db/id (d/entity db ident-or-ref)))

(defn install-demo!
  [conn acl]
  (when-not (entid (d/db conn) :eacl-datahike-demo/demo-seeded)
    (eacl/write-schema! acl default-schema)
    (install-root-fixtures! conn acl)
    (let [account-ids
          (install-accounts! conn acl demo-profile 0 (fn [_ _] nil))]
      (eacl/write-relationships!
       acl
       (mapv
        (fn [account-id]
          (eacl/->RelationshipUpdate
           :touch
           (eacl/->Relationship
            (->object :user "user-1")
            :owner
            (->object :account account-id))))
        (take 2 account-ids))))
    (d/transact conn [{:db/ident :eacl-datahike-demo/demo-seeded}]))
  (assoc ready-progress :total-servers (count-servers (d/db conn))))

(defn- requested-server-counts
  [server-count]
  (loop [remaining server-count
         counts []]
    (if (pos? remaining)
      (let [account-count
            (min remaining (:servers-per-account interactive-profile))]
        (recur (- remaining account-count) (conj counts account-count)))
      counts)))

(defn reserve-seed!
  [!seed-running? server-count max-seed-servers]
  (when-not (and (integer? server-count)
                 (pos? server-count)
                 (<= server-count max-seed-servers))
    (throw (ex-info "Seed size must be a positive supported whole number."
                    {:type :eacl-datahike-demo/invalid-seed-count
                     :http/status 400
                     :error/code "invalid-seed-count"})))
  (when-not (compare-and-set! !seed-running? false true)
    (throw (ex-info "A seed operation is already running."
                    {:type :eacl-datahike-demo/seed-busy
                     :http/status 409
                     :error/code "seed-busy"})))
  true)

(defn seed-reserved!
  ([conn acl !seed-running? !seed-progress server-count]
   (seed-reserved! conn acl !seed-running? !seed-progress server-count
                   default-seed-transaction-size default-seed-pause-ms))
  ([conn acl !seed-running? !seed-progress server-count
    transaction-size pause-ms]
  (let [started (System/nanoTime)
        servers-before (:total-servers @!seed-progress)
        server-counts (requested-server-counts server-count)
        account-start (next-account-number (d/db conn))]
    (try
      (reset! !seed-progress
              {:status :seeding
               :servers-added 0
               :servers-completed 0
               :servers-target server-count
               :total-servers servers-before
               :label "Preparing Datahike transactions"
               :error nil})
      (let [result
            (loop [remaining server-counts
                   account-number account-start
                   completed 0
                   account-ids []]
              (if-let [account-server-count (first remaining)]
                (let [profile (assoc interactive-profile
                                     :accounts 1
                                     :servers-per-account account-server-count)
                      {:keys [account-id entities relationships]}
                      (account-batch account-number profile)
                      completed' (+ completed account-server-count)]
                  (transact-entity-chunks!
                   conn entities transaction-size pause-ms)
                  (write-relationship-chunks!
                   acl relationships transaction-size pause-ms)
                  (reset! !seed-progress
                          {:status :seeding
                           :servers-added 0
                           :servers-completed completed'
                           :servers-target server-count
                           :total-servers (+ servers-before completed')
                           :label (str "Seeded " account-id)
                           :error nil})
                  (recur (next remaining)
                         (inc account-number)
                         completed'
                         (conj account-ids account-id)))
                account-ids))]
        (eacl/write-relationships!
         acl
         (mapv
          (fn [account-id]
            (eacl/->RelationshipUpdate
             :touch
             (eacl/->Relationship
              (->object :user "user-1")
              :owner
              (->object :account account-id))))
          (take 4 result)))
        (let [progress
              {:status :ready
               :servers-added server-count
               :servers-completed server-count
               :servers-target server-count
               :total-servers (+ servers-before server-count)
               :elapsed-ms (/ (double (- (System/nanoTime) started)) 1000000.0)
               :label nil
               :error nil}]
          (reset! !seed-progress progress)
          progress))
      (catch Exception ex
        (let [current @!seed-progress
              progress
              {:status :error
               :servers-added 0
               :servers-completed (:servers-completed current)
               :servers-target server-count
               ;; Keep error reporting O(1). install-demo! reconciles this
               ;; maintained count with durable storage on the next process start.
               :total-servers (:total-servers current)
               :label nil
               :error (ex-message ex)}]
          (reset! !seed-progress progress)
          (throw ex)))
      (finally
        (reset! !seed-running? false))))))

(defn seed-more!
  ([conn acl !seed-running? !seed-progress server-count max-seed-servers]
   (seed-more! conn acl !seed-running? !seed-progress server-count
               max-seed-servers default-seed-transaction-size
               default-seed-pause-ms))
  ([conn acl !seed-running? !seed-progress server-count max-seed-servers
    transaction-size pause-ms]
   (reserve-seed! !seed-running? server-count max-seed-servers)
   (seed-reserved! conn acl !seed-running? !seed-progress server-count
                   transaction-size pause-ms)))

(defn committed-schema-source
  [db]
  (or (d/q '[:find ?source .
             :where
             [?schema :eacl/id "schema-string"]
             [?schema :eacl/schema-string ?source]]
           db)
      ""))

(defn schema-model
  [db]
  (schema/read-schema db))

(defn schema-info
  [db]
  (let [{:keys [relations permissions]} (schema-model db)
        resource-types
        (->> (concat (map :eacl.relation/resource-type relations)
                     (map :eacl.permission/resource-type permissions))
             distinct
             sort
             vec)
        all-types
        (->> (concat resource-types
                     (map :eacl.relation/subject-type relations))
             distinct
             sort
             vec)
        permissions-by-type
        (->> permissions
             (group-by :eacl.permission/resource-type)
             (map (fn [[type entries]]
                    [type (->> entries
                               (map :eacl.permission/permission-name)
                               distinct
                               sort
                               vec)]))
             (into {}))
        child-paths
        (->> relations
             (group-by :eacl.relation/subject-type)
             (map
              (fn [[subject-type entries]]
                [subject-type
                 (->> entries
                      (map (fn [entry]
                             {:resource-type
                              (:eacl.relation/resource-type entry)
                              :relation
                              (:eacl.relation/relation-name entry)}))
                      distinct
                      (sort-by (juxt :resource-type :relation))
                      vec)]))
             (into {}))
        nodes
        (mapv
         (fn [type]
           {:id type
            :permissions (get permissions-by-type type [])})
         all-types)
        links
        (->> relations
             (map (fn [entry]
                    {:source (:eacl.relation/resource-type entry)
                     :target (:eacl.relation/subject-type entry)
                     :label (:eacl.relation/relation-name entry)}))
             distinct
             vec)]
    {:source (committed-schema-source db)
     :resource-types resource-types
     :permissions-by-type permissions-by-type
     :child-paths child-paths
     :nodes nodes
     :links links
     :resource-count (count all-types)
     :relation-count (count relations)
     :permission-count (count permissions)
     :presets schema-presets}))

(defn totals
  ([db]
   (totals db (count-servers db)))
  ([_db server-count]
   (let [base-accounts (:accounts demo-profile)
         base-servers (* base-accounts (:servers-per-account demo-profile))
         extra-servers (max 0 (- server-count base-servers))
         servers-per-account (:servers-per-account interactive-profile)
         extra-accounts (quot (+ extra-servers servers-per-account -1)
                              servers-per-account)
         accounts (+ base-accounts extra-accounts)]
     {:servers server-count
      :accounts accounts
      :teams (+ (* base-accounts (:teams-per-account demo-profile))
                (* extra-accounts (:teams-per-account interactive-profile)))
      :vpcs (+ (* base-accounts (:vpcs-per-account demo-profile))
               (* extra-accounts (:vpcs-per-account interactive-profile)))
      :users (+ 3
                (* base-accounts
                   (+ 1 (:teams-per-account demo-profile)
                      (:vpcs-per-account demo-profile)))
                (* extra-accounts
                   (+ 1 (:teams-per-account interactive-profile)
                      (:vpcs-per-account interactive-profile))))})))

(defn- account-user-ids
  [account-number]
  (let [account-id (str "account-" account-number)
        profile (if (< account-number (:accounts demo-profile))
                  demo-profile
                  interactive-profile)]
    (concat
     [(str account-id "-owner")]
     (map #(str account-id "-team-" % "-leader")
          (range (:teams-per-account profile)))
     (map #(str account-id "-vpc-" % "-admin")
          (range (:vpcs-per-account profile))))))

(defn known-subjects
  [server-count offset limit]
  (let [account-count (:accounts (totals nil server-count))
        all
        (->> (concat ["super-user" "user-1" "user-2"]
                     (mapcat account-user-ids (range account-count)))
             (map (fn [id] {:type :user :id id}))
             (sort-by :id)
             vec)
        total (count all)
        start (min offset total)
        end (min total (+ start limit))]
    {:data (subvec all start end)
     :page-info {:offset start
                 :has-previous-page? (pos? start)
                 :has-next-page? (< end total)
                 :next-offset (when (< end total) end)
                 :total total}}))
