(ns eacl-solidjs.data
  "Standalone demo schema, fixtures, metadata reads, and append-only seeding."
  (:require [datomic.api :as d]
            [eacl.core :as eacl]
            [eacl.datomic.impl :as impl]
            [eacl.datomic.schema :as schema]))

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
  [{:id "default" :label "Default" :schema default-schema}
   {:id "recursive" :label "Recursive" :schema recursive-schema}])

(def quick-subjects
  [{:id "super-user" :label "Super user"}
   {:id "user-1" :label "User 1"}
   {:id "user-2" :label "User 2"}])

(def demo-attributes
  [{:db/ident :demo/type
    :db/doc "EACL SolidJS demo object type."
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

(defn- tx-relationships
  [db relationships]
  (impl/optimistic-relationship-tx-data
   db
   (mapcat #(impl/tx-relationship db % {:allow-tempids? true})
           relationships)))

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

(defn- account-transaction
  [db account-number {:keys [teams-per-account vpcs-per-account
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
                (->object :account account-tempid)
                :account
                (->object :team team-tempid))
               (eacl/->Relationship
                (->object :user leader-tempid)
                :leader
                (->object :team team-tempid))]}))
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
                (->object :account account-tempid)
                :account
                (->object :vpc vpc-tempid))
               (eacl/->Relationship
                (->object :user admin-tempid)
                :shared_admin
                (->object :vpc vpc-tempid))]}))
         (range vpcs-per-account))
        team-tempids (mapv :tempid team-data)
        vpc-tempids (mapv :tempid vpc-data)
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
                (->object :account account-tempid)
                :account
                (->object :server server-tempid))
               (eacl/->Relationship
                (->object :team
                          (nth team-tempids
                               (mod server-number teams-per-account)))
                :team
                (->object :server server-tempid))
               (eacl/->Relationship
                (->object :vpc
                          (nth vpc-tempids
                               (mod server-number vpcs-per-account)))
                :vpc
                (->object :server server-tempid))]}))
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
           (->object :account account-tempid))
          (eacl/->Relationship
           (->object :user owner-tempid)
           :owner
           (->object :account account-tempid))]
         (mapcat :relationships team-data)
         (mapcat :relationships vpc-data)
         (mapcat :relationships server-data))]
    {:account-id account-id
     :tx-data (concat entities (tx-relationships db relationships))}))

(defn- install-root-fixtures!
  [conn acl]
  @(d/transact
    conn
    [{:db/id "platform"
      :db/ident :test/platform
      :eacl/id "platform"
      :demo/type :platform
      :demo/name "Platform"}
     {:db/id "super-user"
      :db/ident :user/super-user
      :eacl/id "super-user"
      :demo/type :user
      :demo/name "Super user"}
     {:db/id "user-1"
      :db/ident :test/user1
      :eacl/id "user-1"
      :demo/type :user
      :demo/name "User 1"}
     {:db/id "user-2"
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
  [conn profile account-start progress!]
  (mapv
   (fn [offset]
     (let [account-number (+ account-start offset)
           {:keys [account-id tx-data]}
           (account-transaction (d/db conn) account-number profile)]
       @(d/transact conn tx-data)
       (progress! account-id (inc offset))
       account-id))
   (range (:accounts profile))))

(defn install-demo!
  [conn acl]
  (when-not (d/entid (d/db conn) :eacl-solidjs/demo-seeded)
    @(d/transact conn demo-attributes)
    (eacl/write-schema! acl default-schema)
    (install-root-fixtures! conn acl)
    (let [account-ids
          (install-accounts! conn demo-profile 0 (fn [_ _] nil))]
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
    @(d/transact conn [{:db/ident :eacl-solidjs/demo-seeded}]))
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
                    {:type :eacl-solidjs/invalid-seed-count
                     :http/status 400
                     :error/code "invalid-seed-count"})))
  (when-not (compare-and-set! !seed-running? false true)
    (throw (ex-info "A seed operation is already running."
                    {:type :eacl-solidjs/seed-busy
                     :http/status 409
                     :error/code "seed-busy"})))
  true)

(defn seed-reserved!
  [conn acl !seed-running? !seed-progress server-count]
  (let [started (System/nanoTime)
        servers-before (count-servers (d/db conn))
        server-counts (requested-server-counts server-count)
        account-start (next-account-number (d/db conn))]
    (try
      (reset! !seed-progress
              {:status :seeding
               :servers-added 0
               :servers-completed 0
               :servers-target server-count
               :total-servers servers-before
               :label "Preparing Datomic transactions"
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
                      {:keys [account-id tx-data]}
                      (account-transaction (d/db conn) account-number profile)
                      completed' (+ completed account-server-count)]
                  @(d/transact conn tx-data)
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
               :total-servers (count-servers (d/db conn))
               :elapsed-ms (/ (double (- (System/nanoTime) started)) 1000000.0)
               :label nil
               :error nil}]
          (reset! !seed-progress progress)
          progress))
      (catch Exception ex
        (let [progress
              {:status :error
               :servers-added 0
               :servers-completed (:servers-completed @!seed-progress)
               :servers-target server-count
               :total-servers (count-servers (d/db conn))
               :label nil
               :error (ex-message ex)}]
          (reset! !seed-progress progress)
          (throw ex)))
      (finally
        (reset! !seed-running? false)))))

(defn seed-more!
  [conn acl !seed-running? !seed-progress server-count max-seed-servers]
  (reserve-seed! !seed-running? server-count max-seed-servers)
  (seed-reserved! conn acl !seed-running? !seed-progress server-count))

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
  [db]
  (into
   {:servers (count-servers db)}
   (map
    (fn [type]
      [(keyword (str (name type) "s"))
       (d/q '[:find (count ?entity) .
              :in $ ?type
              :where [?entity :demo/type ?type]]
            db type)]))
   [:user :account :team :vpc :server]))

(defn known-subjects
  [db offset limit]
  (let [all
        (->> (d/q '[:find [?id ...]
                    :where
                    [?entity :demo/type :user]
                    [?entity :eacl/id ?id]]
                  db)
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
