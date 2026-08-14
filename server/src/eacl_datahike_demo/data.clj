(ns eacl-datahike-demo.data
  "Standalone demo schema, fixtures, metadata reads, and append-only seeding."
  (:require [datahike.api :as d]
            [eacl.core :as eacl]
            [eacl.datahike.schema :as schema])
  (:import (java.lang.management ManagementFactory)))

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
    :db/index true}
   {:db/ident :demo/server-count
    :db/doc "Durable server-resource contribution used for O(accounts) startup totals."
    :db/cardinality :db.cardinality/one
    :db/valueType :db.type/long}
   {:db/ident :demo/account-number
    :db/doc "Deterministic seed account ordinal used for resumable imports."
    :db/cardinality :db.cardinality/one
    :db/valueType :db.type/long
    :db/index true}
   {:db/ident :demo/server-number
    :db/doc "Server ordinal within its deterministic seed account."
    :db/cardinality :db.cardinality/one
    :db/valueType :db.type/long
    :db/index true}
   {:db/ident :demo/total-servers
    :db/doc "Durable exact server total for constant-time bootstrap reads."
    :db/cardinality :db.cardinality/one
    :db/valueType :db.type/long}
   {:db/ident :demo/account-count
    :db/doc "Durable completed account total."
    :db/cardinality :db.cardinality/one
    :db/valueType :db.type/long}
   {:db/ident :demo/team-count
    :db/doc "Durable completed team total."
    :db/cardinality :db.cardinality/one
    :db/valueType :db.type/long}
   {:db/ident :demo/vpc-count
    :db/doc "Durable completed VPC total."
    :db/cardinality :db.cardinality/one
    :db/valueType :db.type/long}
   {:db/ident :demo/user-count
    :db/doc "Durable completed fixture user total."
    :db/cardinality :db.cardinality/one
    :db/valueType :db.type/long}])

(def demo-profile
  {:accounts 4
   :teams-per-account 2
   :vpcs-per-account 1
   :servers-per-account 12
   :recursive? true})

(def interactive-profile
  {:teams-per-account 4
   :vpcs-per-account 2
   :recursive? true})

(def default-seed-transaction-size 1000)
(def default-seed-pause-ms 0)
(def default-seed-in-flight 2)
(def seed-account-group-size 4)
(def seed-server-group-size 8)
(def fixture-plan-version 1)

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

(defn- await-datahike!
  [result]
  (let [value (if (instance? clojure.lang.IDeref result) @result result)]
    (if (instance? Throwable value)
      (throw value)
      value)))

(defn- transact!
  [conn tx-data]
  (await-datahike! (d/transact conn tx-data)))

(defn count-servers
  [db]
  (d/q '[:find (count ?server) .
         :where [?server :server/name]]
       db))

(defn maintained-server-count
  "Returns the durable constant-time server total, with an old-store fallback."
  [db]
  (or (d/q '[:find ?count .
             :where
             [?totals :db/ident :eacl-datahike-demo/totals]
             [?totals :demo/total-servers ?count]]
           db)
      (when (d/q '[:find ?attribute .
                   :where [?attribute :db/ident :demo/server-count]]
                 db)
        (let [rows (d/q '[:find ?account ?count
                          :where
                          [?account :demo/type :account]
                          [?account :demo/server-count ?count]]
                        db)]
          (when (seq rows)
            (reduce + (map second rows)))))))

(defn- parse-account-number
  [account-id]
  (when-let [[_ digits] (re-matches #"account-(\d+)" (str account-id))]
    (Long/parseLong ^String digits)))

(defn- next-account-number
  [db]
  (or (d/q '[:find ?count .
             :where
             [?totals :db/ident :eacl-datahike-demo/totals]
             [?totals :demo/account-count ?count]]
           db)
      (let [accounts
        (->> (d/q '[:find ?account-id
                    :where
                    [?account :demo/type :account]
                    [?account :eacl/id ?account-id]]
                  db)
             (map first)
             (keep (fn [account-id]
                     (when-let [number (parse-account-number account-id)]
                       [number account-id])))
             (sort-by first)
             vec)
        completed
        (->> (d/q '[:find [?account-id ...]
                     :where
                     [?account :demo/type :account]
                     [?account :eacl/id ?account-id]
                     [?account :demo/server-count]]
                   db)
             set)]
        (or (some (fn [[number account-id]]
                    (when-not (contains? completed account-id) number))
                  accounts)
            (inc (reduce max -1 (map first accounts)))))))

(defn weighted-account-server-count
  "Return a reproducible 1..50,000 account size weighted toward small accounts.
  Across a large plan the expected mean is 4,978 servers."
  [account-number]
  (let [random (java.util.SplittableRandom.
                (long (+ 20260813 (* 104729 account-number))))
        selection (.nextDouble random)
        [minimum maximum]
        (cond
          (< selection 0.55) [1 2000]
          (< selection 0.84) [2001 7500]
          (< selection 0.96) [7501 20000]
          :else [20001 50000])]
    (.nextLong random (long minimum) (long (inc maximum)))))

(defn requested-account-plan
  "Plan exact deterministic account sizes from an absolute account ordinal."
  [account-start server-count]
  (loop [account-number account-start
         remaining server-count
         plan []]
    (if (pos? remaining)
      (let [server-count (min remaining
                              (weighted-account-server-count account-number))]
        (recur (inc account-number)
               (- remaining server-count)
               (conj plan {:account-number account-number
                           :server-count server-count})))
      plan)))

(defn- recursive-account-parent-number
  [account-number]
  (when (and (pos? account-number)
             (not (zero? (mod account-number seed-account-group-size))))
    (dec account-number)))

(defn- account-batch
  [account-number {:keys [teams-per-account vpcs-per-account
                          servers-per-account recursive?]}]
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
                :server/name (str "Server " (inc server-number))
                :demo/server-number server-number}]
              :relationships
              (cond->
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
                 (->object :server server-id))]
                (and recursive?
                     (pos? server-number)
                     (not (zero? (mod server-number seed-server-group-size))))
                (conj
                 (eacl/->Relationship
                  (->object :server
                            (str account-id "-server-" (dec server-number)))
                  :parent
                  (->object :server server-id))))}))
         (range servers-per-account))
        entities
        (concat
         [{:db/id account-tempid
           :eacl/id account-id
           :demo/type :account
           :demo/name (str "Account " (inc account-number))
           :demo/account-number account-number}
          {:db/id owner-tempid
           :eacl/id owner-id
           :demo/type :user
           :demo/name (str "Owner · " account-id)}]
         (mapcat :entities team-data)
         (mapcat :entities vpc-data)
         (mapcat :entities server-data))
        relationships
        (concat
         (cond->
          [(eacl/->Relationship
           (->object :platform "platform")
           :platform
           (->object :account account-id))
          (eacl/->Relationship
           (->object :user owner-id)
           :owner
           (->object :account account-id))]
           (and recursive? (recursive-account-parent-number account-number))
           (conj
            (eacl/->Relationship
             (->object :account
                       (str "account-"
                            (recursive-account-parent-number account-number)))
             :parent
             (->object :account account-id))))
         (mapcat :relationships team-data)
         (mapcat :relationships vpc-data)
         (mapcat :relationships server-data))]
    {:account-id account-id
     :server-count servers-per-account
     :team-count teams-per-account
     :vpc-count vpcs-per-account
     :entities (vec entities)
     :relationships (vec relationships)}))

(defn- write-relationships!
  [acl relationships]
  (when (seq relationships)
    (eacl/write-relationships!
     acl
     (mapv #(eacl/->RelationshipUpdate :touch %) relationships))))

(defn- record-account-server-count!
  [conn account-id server-count team-count vpc-count]
  (let [db (d/db conn)
        account (d/entity db [:eacl/id account-id])]
    (when-not (:demo/server-count account)
      (let [totals (d/entity db :eacl-datahike-demo/totals)]
        (transact!
         conn
         [{:db/id [:eacl/id account-id]
           :demo/server-count server-count}
          {:db/id [:db/ident :eacl-datahike-demo/totals]
           :demo/total-servers (+ (:demo/total-servers totals) server-count)
           :demo/account-count (inc (:demo/account-count totals))
           :demo/team-count (+ (:demo/team-count totals) team-count)
           :demo/vpc-count (+ (:demo/vpc-count totals) vpc-count)
           :demo/user-count (+ (:demo/user-count totals)
                               1 team-count vpc-count)}])))))

(defn- paced!
  [pause-ms]
  (when (pos? pause-ms)
    (Thread/sleep pause-ms)))

(def ^:private seed-gc-threshold 0.65)

(defn- request-seed-gc-if-needed!
  "Bound import allocation pressure at a completed transaction window.
  Datahike/S3 index rewriting can produce several GiB of reclaimable heap even
  though the connected database's live set is much smaller."
  []
  (let [usage (.getHeapMemoryUsage (ManagementFactory/getMemoryMXBean))
        maximum (.getMax usage)
        used (.getUsed usage)]
    (when (and (pos? maximum)
               (>= (/ (double used) maximum) seed-gc-threshold))
      (System/gc)
      true)))

(defn- submit-windows!
  [items in-flight submit! pause-ms]
  (doseq [window (partition-all in-flight items)]
    (let [jobs (mapv #(future (submit! %)) window)
          results (mapv (fn [job]
                          (try
                            @job
                            nil
                            (catch Throwable throwable throwable)))
                        jobs)]
      (when-let [failure (some #(when (instance? Throwable %) %) results)]
        (throw failure)))
    ;; A completed in-flight window is a safe point: all submitted Datahike
    ;; transactions are durable and no application future still uses the
    ;; allocation-heavy pre-commit database values.
    (request-seed-gc-if-needed!)
    (paced! pause-ms)))

(defn- transact-entity-chunks!
  [conn entities transaction-size in-flight pause-ms]
  (submit-windows!
   (partition-all transaction-size entities)
   in-flight
   #(transact! conn (vec %))
   pause-ms))

(defn- write-relationship-chunks!
  [acl relationships transaction-size in-flight pause-ms]
  (submit-windows!
   (partition-all transaction-size relationships)
   in-flight
   #(write-relationships! acl %)
   pause-ms))

(defn- account-scaffold
  [account-number server-count]
  (let [teams-per-account (min (:teams-per-account interactive-profile)
                               server-count)
        vpcs-per-account (min (:vpcs-per-account interactive-profile)
                              server-count)
        profile (assoc interactive-profile
                       :servers-per-account 0
                       :teams-per-account teams-per-account
                       :vpcs-per-account vpcs-per-account)
        scaffold (account-batch account-number profile)]
    (assoc scaffold
           :server-count server-count
           :teams-per-account teams-per-account
           :vpcs-per-account vpcs-per-account)))

(defn- server-entity
  [account-id server-number]
  {:db/id (d/tempid :db.part/user)
   :eacl/id (str account-id "-server-" server-number)
   :demo/type :server
   :demo/name (str "Server " (inc server-number) " · " account-id)
   :server/name (str "Server " (inc server-number))
   :demo/server-number server-number})

(defn- server-relationships
  [account-id teams-per-account vpcs-per-account server-number]
  (let [server (->object :server (str account-id "-server-" server-number))]
    (cond->
     [(eacl/->Relationship
       (->object :account account-id) :account server)
      (eacl/->Relationship
       (->object :team
                 (str account-id "-team-"
                      (mod server-number teams-per-account)))
       :team server)
      (eacl/->Relationship
       (->object :vpc
                 (str account-id "-vpc-"
                      (mod server-number vpcs-per-account)))
       :vpc server)]
      (and (pos? server-number)
           (not (zero? (mod server-number seed-server-group-size))))
      (conj
       (eacl/->Relationship
        (->object :server
                  (str account-id "-server-" (dec server-number)))
        :parent server)))))

(defn- seed-account!
  [conn acl account-number server-count transaction-size in-flight pause-ms]
  (let [{:keys [account-id entities relationships
                teams-per-account vpcs-per-account]}
        (account-scaffold account-number server-count)
        server-numbers (range server-count)]
    ;; A repeated attempt upserts the deterministic scaffold and relationships.
    (transact! conn entities)
    (write-relationships! acl relationships)
    (submit-windows!
     (partition-all transaction-size server-numbers)
     in-flight
     (fn [numbers]
       (transact! conn (mapv #(server-entity account-id %) numbers)))
     pause-ms)
    (write-relationship-chunks!
     acl
     (mapcat #(server-relationships account-id teams-per-account
                                    vpcs-per-account %)
             server-numbers)
     transaction-size in-flight pause-ms)
    (record-account-server-count! conn account-id server-count
                                  teams-per-account vpcs-per-account)
    {:account-id account-id
     :account-number account-number
     :server-count server-count}))

(defn- install-root-fixtures!
  [conn acl]
  (transact!
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
  (transact!
   conn
   [{:db/ident :eacl-datahike-demo/totals
     :demo/total-servers 0
     :demo/account-count 0
     :demo/team-count 0
     :demo/vpc-count 0
     :demo/user-count 3}])
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
           {:keys [account-id server-count team-count vpc-count
                   entities relationships]}
           (account-batch account-number profile)]
       (transact! conn entities)
       (write-relationships! acl relationships)
       (record-account-server-count! conn account-id server-count
                                     team-count vpc-count)
       (progress! account-id (inc offset))
       account-id))
   (range (:accounts profile))))

(defn- entid
  [db ident-or-ref]
  (:db/id (d/entity db ident-or-ref)))

(defn install-demo!
  ([conn acl]
   (install-demo! conn acl nil))
  ([conn acl legacy-server-count]
   (doseq [attribute demo-attributes
           :when (not (entid (d/db conn) (:db/ident attribute)))]
     (transact! conn [attribute]))
   (when-not (entid (d/db conn) :eacl-datahike-demo/demo-seeded)
     (eacl/write-schema! acl recursive-schema)
     (install-root-fixtures! conn acl)
     (let [account-ids
           (install-accounts! conn acl demo-profile 0 (fn [_ _] nil))]
       (eacl/write-relationships!
        acl
        (mapv
         (fn [account-id user-id]
           (eacl/->RelationshipUpdate
            :touch
            (eacl/->Relationship
             (->object :user user-id)
             :owner
             (->object :account account-id))))
         [(first account-ids) (second account-ids)]
         ["user-1" "user-2"])))
     (transact! conn [{:db/ident :eacl-datahike-demo/demo-seeded}]))
   (let [db (d/db conn)
         maintained-total (maintained-server-count db)
         total (or maintained-total
                   legacy-server-count
                   (count-servers db))]
     (when-not maintained-total
       (transact! conn [{:db/ident :eacl-datahike-demo/server-total
                          :demo/server-count total}]))
     (assoc ready-progress :total-servers total))))

(defn- quick-user-account-assignments
  [seeded-accounts]
  (let [candidates (or (seq (filter #(= (dec seed-account-group-size)
                                         (mod (:account-number %)
                                              seed-account-group-size))
                                    seeded-accounts))
                       seeded-accounts)]
    (->> candidates
         (sort-by (juxt #(Math/abs (long (- (:server-count %) 5000)))
                        :account-number))
         (take 2)
         (map :account-id)
         vec)))

(defn- install-large-quick-user-access!
  [conn acl seeded-accounts]
  (when (and (seq seeded-accounts)
             (nil? (entid (d/db conn)
                          :eacl-datahike-demo/large-quick-users-assigned)))
    (let [account-ids (quick-user-account-assignments seeded-accounts)
          user-ids ["user-1" "user-2"]]
      (write-relationships!
       acl
       (mapv (fn [user-id account-id]
               (eacl/->Relationship
                (->object :user user-id)
                :owner
                (->object :account account-id)))
             user-ids account-ids))
      (transact! conn [{:db/ident
                         :eacl-datahike-demo/large-quick-users-assigned}]))))

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
                   default-seed-transaction-size default-seed-pause-ms
                   default-seed-in-flight))
  ([conn acl !seed-running? !seed-progress server-count
    transaction-size pause-ms]
   (seed-reserved! conn acl !seed-running? !seed-progress server-count
                   transaction-size pause-ms default-seed-in-flight))
  ([conn acl !seed-running? !seed-progress server-count
    transaction-size pause-ms in-flight]
  (let [started (System/nanoTime)
        servers-before (:total-servers @!seed-progress)
        account-start (next-account-number (d/db conn))
        account-plan (requested-account-plan account-start server-count)]
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
            (loop [remaining account-plan
                   completed 0
                   seeded-accounts []]
              (if-let [{:keys [account-number]
                        account-server-count :server-count}
                       (first remaining)]
                (let [_ (swap! !seed-progress assoc
                               :label (str "Seeding account-" account-number
                                           " (" account-server-count " servers)"))
                      seeded-account
                      (seed-account! conn acl account-number account-server-count
                                     transaction-size in-flight pause-ms)
                      completed' (+ completed account-server-count)]
                  (reset! !seed-progress
                          {:status :seeding
                           :servers-added 0
                           :servers-completed completed'
                           :servers-target server-count
                           :total-servers (+ servers-before completed')
                           :label (str "Seeded " (:account-id seeded-account))
                           :error nil})
                  (request-seed-gc-if-needed!)
                  (recur (next remaining)
                         completed'
                         (conj seeded-accounts seeded-account)))
                seeded-accounts))]
        (install-large-quick-user-access! conn acl result)
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
               default-seed-pause-ms default-seed-in-flight))
  ([conn acl !seed-running? !seed-progress server-count max-seed-servers
    transaction-size pause-ms]
   (seed-more! conn acl !seed-running? !seed-progress server-count
               max-seed-servers transaction-size pause-ms
               default-seed-in-flight))
  ([conn acl !seed-running? !seed-progress server-count max-seed-servers
    transaction-size pause-ms in-flight]
   (reserve-seed! !seed-running? server-count max-seed-servers)
   (seed-reserved! conn acl !seed-running? !seed-progress server-count
                   transaction-size pause-ms in-flight)))

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
  ([db server-count]
   (if-let [durable
            (when db
              (let [entity (d/entity db :eacl-datahike-demo/totals)]
                (when (:demo/total-servers entity)
                  {:servers (:demo/total-servers entity)
                   :accounts (:demo/account-count entity)
                   :teams (:demo/team-count entity)
                   :vpcs (:demo/vpc-count entity)
                   :users (:demo/user-count entity)})))]
     durable
     (let [base-accounts (:accounts demo-profile)
           base-servers (* base-accounts (:servers-per-account demo-profile))
           extra-servers (max 0 (- server-count base-servers))
           account-plan (requested-account-plan base-accounts extra-servers)
           account-shape
           (fn [{:keys [server-count]}]
             {:teams (min (:teams-per-account interactive-profile)
                          server-count)
              :vpcs (min (:vpcs-per-account interactive-profile)
                         server-count)})
           shapes (map account-shape account-plan)
           extra-accounts (count account-plan)
           extra-teams (reduce + 0 (map :teams shapes))
           extra-vpcs (reduce + 0 (map :vpcs shapes))]
       {:servers server-count
        :accounts (+ base-accounts extra-accounts)
        :teams (+ (* base-accounts (:teams-per-account demo-profile))
                  extra-teams)
        :vpcs (+ (* base-accounts (:vpcs-per-account demo-profile))
                 extra-vpcs)
        :users (+ 3
                  (* base-accounts
                     (+ 1 (:teams-per-account demo-profile)
                        (:vpcs-per-account demo-profile)))
                  extra-accounts extra-teams extra-vpcs)}))))

(defn- account-user-ids
  [account-number server-count]
  (let [account-id (str "account-" account-number)
        profile (if (< account-number (:accounts demo-profile)) demo-profile
                    {:teams-per-account
                     (min (:teams-per-account interactive-profile) server-count)
                     :vpcs-per-account
                     (min (:vpcs-per-account interactive-profile) server-count)})]
    (concat
     [(str account-id "-owner")]
     (map #(str account-id "-team-" % "-leader")
          (range (:teams-per-account profile)))
     (map #(str account-id "-vpc-" % "-admin")
          (range (:vpcs-per-account profile))))))

(defn known-subjects
  ([server-count offset limit]
   (known-subjects nil server-count offset limit))
  ([db server-count offset limit]
  (let [base-accounts (:accounts demo-profile)
        base-servers (* base-accounts (:servers-per-account demo-profile))
        planned-accounts
        (concat
         (map (fn [account-number]
                {:account-number account-number
                 :server-count (:servers-per-account demo-profile)})
              (range base-accounts))
         (requested-account-plan base-accounts
                                 (max 0 (- server-count base-servers))))
        all
        (->> (or (when db
                   (seq (d/q '[:find [?user-id ...]
                               :where
                               [?user :demo/type :user]
                               [?user :eacl/id ?user-id]]
                             db)))
                 (concat ["super-user" "user-1" "user-2"]
                         (mapcat (fn [{:keys [account-number server-count]}]
                                   (account-user-ids account-number server-count))
                                 planned-accounts)))
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
                 :total total}})))
