(ns eacl-solidjs.http
  "Jetty async bridge from HTTP lifecycle events to EACL cancellation."
  (:require [eacl.core :as eacl]
            [eacl-solidjs.contracts :as contracts])
  (:import [jakarta.servlet AsyncContext AsyncEvent AsyncListener]
           [java.util.concurrent TimeUnit]
           [java.util.concurrent.atomic AtomicReference]
           [org.eclipse.jetty.http HttpGenerator]
           [org.eclipse.jetty.io Connection Connection$Listener]
           [org.eclipse.jetty.server HttpChannel Request Server]
           [org.eclipse.jetty.server.handler HandlerWrapper]
           [org.eclipse.jetty.util.thread Scheduler Scheduler$Task]))

(def ^:dynamic *request-control* nil)

(def ^:private disconnect-heartbeat-ms 250)

(defn- eacl-read-request?
  [^Request request]
  (some-> request .getRequestURI (.startsWith "/api/eacl/")))

(defn- transition!
  [{:keys [^AtomicReference state token ^AtomicReference worker-thread
           interruptible?]}
   expected next-state]
  (when (.compareAndSet state expected next-state)
    (when (contains? #{:disconnected :timed-out} next-state)
      (eacl/cancel! token)
      ;; Cooperative checkpoints cannot preempt a blocking backend call. Read
      ;; requests may interrupt that worker as a bounded Datomic backstop;
      ;; mutations never use this path.
      (when (and interruptible? worker-thread)
        (when-let [^Thread worker (.get worker-thread)]
          (.interrupt worker))))
    true))

(defn- remove-connection-listener!
  [{:keys [^Connection connection listener heartbeat-task]}]
  (when-let [^Scheduler$Task task (some-> heartbeat-task deref)]
    (.cancel task)
    (reset! heartbeat-task nil))
  (when (and connection listener)
    (try
      (.removeEventListener connection listener)
      (catch Throwable _ nil))))

(defn- complete-safely!
  [^AsyncContext context]
  (try
    (.complete context)
    (catch Throwable _ nil)))

(defn- request-control
  [^Request request]
  (let [token (eacl/cancellation-token)
        state (AtomicReference. :running)
        connection (some-> request .getHttpChannel .getConnection)
        control-ref (atom nil)
        listener
        (reify Connection$Listener
          (onOpened [_ _])
          (onClosed [_ _]
            (when-let [control @control-ref]
              (transition! control :running :disconnected))))
        control {:token token
                 :state state
                 :worker-thread (AtomicReference.)
                 :interruptible? (eacl-read-request? request)
                 :request request
                 :connection connection
                 :listener listener
                 :heartbeat-task (atom nil)}]
    (reset! control-ref control)
    (when connection
      (.addEventListener connection listener))
    control))

(defn- start-disconnect-heartbeat!
  "Sends an uncommitted 102 response while work is running. Servlet APIs do not
  expose a portable client-disconnect callback; this bounded probe makes Jetty
  observe an aborted socket without committing the eventual JSON response."
  [control ^AsyncContext context]
  (let [^Request request (:request control)
        ^HttpChannel channel (.getHttpChannel request)
        ^Scheduler scheduler (.getScheduler channel)
        heartbeat-task (:heartbeat-task control)]
    (when scheduler
      (letfn [(schedule! []
                (when (= :running (.get ^AtomicReference (:state control)))
                  (reset! heartbeat-task
                          (.schedule scheduler
                                     ^Runnable
                                     (reify Runnable
                                       (^void run [_]
                                         (when (= :running
                                                  (.get ^AtomicReference
                                                        (:state control)))
                                           (try
                                             (.sendResponse
                                              channel
                                              HttpGenerator/PROGRESS_102_INFO
                                              nil true)
                                             (schedule!)
                                             (catch Throwable _
                                               (when (transition!
                                                      control :running
                                                      :disconnected)
                                                 (complete-safely!
                                                  context)))))))
                                     disconnect-heartbeat-ms
                                     TimeUnit/MILLISECONDS))))]
        (schedule!)))))

(defn configure-server!
  "Wraps Ring's Jetty handler so each request owns a cancellation token and
  observes closure of the upstream connection. Called before server start."
  [^Server server]
  (let [delegate (.getHandler server)
        wrapper
        (proxy [HandlerWrapper] []
          (handle [target ^Request base-request request response]
            (let [control (request-control base-request)]
              (try
                (binding [*request-control* control]
                  (proxy-super handle target base-request request response))
                (catch Throwable throwable
                  (remove-connection-listener! control)
                  (throw throwable))))))]
    (.setHandler wrapper delegate)
    (.setHandler server wrapper))
  server)

(defn- timeout-response
  [request]
  (contracts/response
   request 504
   {:error {:code "execution-timeout"
            :message "The server could not complete the request."}
    :meta {:request-id (:request-id request)}}))

(defn asynchronous-handler
  "Adapts a synchronous Ring handler to Ring's async arity.

  Jetty timeout/error and upstream connection-close events signal the same
  token supplied to EACL. A terminal-state CAS prevents a late worker from
  writing after a timeout or disconnect."
  [handler]
  (fn [request respond raise]
    (let [control (or *request-control*
                      {:token (eacl/cancellation-token)
                       :state (AtomicReference. :running)})
          ^Request servlet-request (:request control)
          ^AsyncContext context (when servlet-request
                                  (.getAsyncContext servlet-request))
          request (assoc request
                         :request-id (contracts/request-id request)
                         :eacl/cancellation-token (:token control))]
      (if-not context
        (try
          (respond (handler request))
          (catch Throwable throwable
            (raise throwable)))
        (do
          (.addListener
           context
           (reify AsyncListener
             (^void onTimeout [_ ^AsyncEvent _]
               (when (transition! control :running :timed-out)
                 (try
                   (respond (timeout-response request))
                   (catch Throwable _
                     (complete-safely! context)))))
             (^void onError [_ ^AsyncEvent _]
               (transition! control :running :disconnected)
               (remove-connection-listener! control))
             (^void onComplete [_ ^AsyncEvent _]
               (.set ^AtomicReference (:state control) :complete)
               (remove-connection-listener! control))
             (^void onStartAsync [_ ^AsyncEvent _])))
          (start-disconnect-heartbeat! control context)
          (.start
           context
           (reify Runnable
             (^void run [_]
               (let [^AtomicReference worker-thread (:worker-thread control)]
                 (.set worker-thread (Thread/currentThread))
                 (try
                   (let [response (handler request)]
                     (when (transition! control :running :responding)
                       (respond response)))
                   (catch Throwable throwable
                     (when (transition! control :running :responding)
                       (raise throwable)))
                   (finally
                     (.set worker-thread nil))))))))))))
