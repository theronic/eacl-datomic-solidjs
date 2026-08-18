(ns eacl-solidjs.http-test
  (:require [clojure.test :refer [deftest is]]
            [eacl.core :as eacl]
            [eacl-solidjs.http :as http]
            [ring.adapter.jetty :as jetty])
  (:import [java.net InetSocketAddress Socket URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent CountDownLatch TimeUnit]
           [org.eclipse.jetty.server Server ServerConnector]))

(defn- start-test-server
  [handler async-timeout]
  (jetty/run-jetty
   (http/asynchronous-handler handler)
   {:host "127.0.0.1"
    :port 0
    :join? false
    :async? true
    :async-timeout async-timeout
    :max-idle-time 5000
    :configurator http/configure-server!}))

(defn- local-port
  [^Server server]
  (.getLocalPort ^ServerConnector (first (.getConnectors server))))

(defn- write-request!
  ([socket]
   (write-request! socket "/slow"))
  ([^Socket socket path]
   (let [request (.getBytes
                  (str "GET " path
                       " HTTP/1.1\r\nHost: localhost\r\n"
                       "Connection: close\r\n\r\n")
                  StandardCharsets/US_ASCII)]
     (.write (.getOutputStream socket) request)
     (.flush (.getOutputStream socket)))))

(defn- get-response
  [port]
  (.send (HttpClient/newHttpClient)
         (-> (HttpRequest/newBuilder)
             (.uri (URI/create (str "http://127.0.0.1:" port "/slow")))
             (.GET)
             (.build))
         (HttpResponse$BodyHandlers/ofString)))

(deftest heartbeat-does-not-commit-the-final-response
  (let [server
        (start-test-server
         (fn [_]
           (Thread/sleep 400)
           {:status 200
            :headers {"content-type" "application/json"}
            :body "{\"status\":\"finished\"}"})
         2000)]
    (try
      (let [response (get-response (local-port server))]
        (is (= 200 (.statusCode response)))
        (is (= "{\"status\":\"finished\"}" (.body response))))
      (finally
        (.stop server)
        (.join server)))))

(deftest async-timeout-cancels-running-handler
  (let [cancelled (CountDownLatch. 1)
        interrupted (CountDownLatch. 1)
        handler
        (fn [request]
          (let [token (:eacl/cancellation-token request)
                deadline (+ (System/nanoTime) 5000000000)]
            (try
              (loop []
                (cond
                  (eacl/cancelled? token)
                  (.countDown cancelled)

                  (< (System/nanoTime) deadline)
                  (do (Thread/sleep 5) (recur))))
              (catch InterruptedException _
                (.countDown interrupted)
                (when (eacl/cancelled? token)
                  (.countDown cancelled))))
            {:status 200 :headers {} :body "late"}))
        server (start-test-server handler 100)]
    (try
      (let [response (get-response (local-port server))]
        (is (= 504 (.statusCode response)))
        (is (.await cancelled 2 TimeUnit/SECONDS))
        ;; /slow is not an EACL route, so cancellation remains cooperative.
        (is (false? (.await interrupted 100 TimeUnit/MILLISECONDS))))
      (finally
        (.stop server)
        (.join server)))))

(deftest eacl-timeout-interrupts-a-blocking-backend-call
  (let [interrupted (CountDownLatch. 1)
        server
        (start-test-server
         (fn [_]
           (try
             (Thread/sleep 5000)
             (catch InterruptedException _
               (.countDown interrupted)))
           {:status 200 :headers {} :body "late"})
         100)]
    (try
      (with-open [socket (Socket.)]
        (.connect socket (InetSocketAddress. "127.0.0.1"
                                             (local-port server)))
        (write-request! socket "/api/eacl/lookup-resources")
        (is (.await interrupted 2 TimeUnit/SECONDS)))
      (finally
        (.stop server)
        (.join server)))))

(deftest closing-client-connection-cancels-running-handler
  (let [started (CountDownLatch. 1)
        cancelled (CountDownLatch. 1)
        observed-token (atom nil)
        handler
        (fn [request]
          (let [token (:eacl/cancellation-token request)
                deadline (+ (System/nanoTime) 5000000000)]
            (reset! observed-token token)
            (.countDown started)
            (loop []
              (cond
                (eacl/cancelled? token)
                (.countDown cancelled)

                (< (System/nanoTime) deadline)
                (do (Thread/sleep 5) (recur))))
            {:status 200 :headers {} :body "finished"}))
        server (start-test-server handler 5000)]
    (try
      (with-open [socket (Socket.)]
        (.connect socket (InetSocketAddress. "127.0.0.1"
                                             (local-port server)))
        ;; Browser/proxy cancellation aborts the upstream exchange rather than
        ;; performing a graceful HTTP half-close.
        (.setSoLinger socket true 0)
        (write-request! socket)
        (is (.await started 2 TimeUnit/SECONDS))
        ;; Exercise a disconnect after at least one successful heartbeat. A
        ;; one-shot probe only detects clients that disappear immediately.
        (Thread/sleep 400))
      (is (.await cancelled 2 TimeUnit/SECONDS))
      (is (eacl/cancellation-token? @observed-token))
      (is (eacl/cancelled? @observed-token))
      (finally
        (.stop server)
        (.join server)))))
