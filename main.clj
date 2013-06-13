; -*- mode: clojure; -*-
; vim: filetype=clojure
(require '[clj-http.client :as client] 
         '[cheshire.core :as json]
         '[riemann.query :as query])

(def hostname (.getHostName (java.net.InetAddress/getLocalHost)))

(include "alerta.clj")

; configure the various servers that we listen on
(tcp-server :host "0.0.0.0")
(udp-server :host "0.0.0.0")
(ws-server)
(repl-server)
; listen on the carbon protocol
(graphite-server :host "0.0.0.0"
                 :port 3003
                 :protocol :udp
                 ; assume that all incoming carbon metrics have a name in the form
                 ; ENV.GRID.CLUSTER.HOSTNAME.SERVICE
                 :parser-fn (fn [{:keys [service] :as event}]
                              (if-let [[env grid cluster host metric]
                                       (clojure.string/split service #"\.")]
                                (if-let [[metric instance]
                                         (clojure.string/split metric #"-" 2)]
                                  {:host host
                                   :service metric
                                   :environment env
                                   :resource (if (nil? instance) host (str host ":" instance))
                                   :grid grid
                                   :cluster cluster}))))

(defn log-info
  [e]
  (info e))

(def graph
  (if (resolve 'local-testing)
    log-info
    (graphite {:host hostname
               :path (fn [e] (str "riemann." (riemann.graphite/graphite-path-basic e)))})))

; reap expired events every 10 seconds
(periodically-expire 10 {:keep-keys [:host :service :index-time]})

; some helpful functions
(defn now []
  (Math/floor (unix-time)))

(defn switch-epoch-to-elapsed
  [& children]
  (fn [e] ((apply with {:metric (- (now) (:metric e))} children) e)))

(defn lookup-metric
  [metricname & children]
  (let [metricsymbol (keyword metricname)]
    (fn [e]
      (let [metricevent (.lookup (:index @core) (:host e) metricname)]
        (if-let [metricvalue (:metric metricevent)]
          (call-rescue (assoc e metricsymbol metricvalue) children))))))

; set of severity functions
(defn severity
  [severity message & children]
  (fn [e] ((apply with {:state severity :description message} children) e)))

(def informational (partial severity "informational"))
(def normal (partial severity "normal"))
(def warning (partial severity "warning"))
(def minor (partial severity "minor"))
(def major (partial severity "major"))
(def critical (partial severity "critical"))

(defn edge-detection
  [samples & children]
  (let [detector (by [:host :service] (runs samples :state (apply changed :state children)))]
    (fn [e] (detector e))))

(defn set-resource-from-cluster [e] (assoc e :resource (:cluster e)))

; thresholding
(let [index (default :ttl 900 (update-index (index)))
      dedup-alert (edge-detection 1 log-info alerta)
      dedup-2-alert (edge-detection 2 log-info alerta)
      dedup-4-alert (edge-detection 4 log-info alerta)]
  (streams
   (with :index-time (format "%.0f" (now)) index))

  (streams
   (expired
    log-info))

  (streams
   (throttle 1 30 heartbeat))

  (streams
   (let [hosts (atom #{})]
     (fn [event]
       (swap! hosts conj (:host event))
       (index {:service "unique hosts"
               :time (unix-time)
               :metric (count @hosts)})
       ((throttle 1 5 graph) {:service "riemann unique_hosts"
                              :host hostname
                              :time (unix-time)
                              :metric (count @hosts)}))))

  (streams
   (let [metrics (atom #{})]
     (fn [event]
       (swap! metrics conj {:host (:host event) :service (:service event)})
       (index {:service "unique services"
               :time (unix-time)
               :metric (count @metrics)})
       ((throttle 1 5 graph) {:service "riemann unique_services"
                              :host hostname
                              :time (unix-time)
                              :metric (count @metrics)}))))

  (streams
   (let [boot-threshold 
         (match :service "boottime"
                (where* 
                 (fn [e] 
                   (let [boot-threshold (- (now) 7200)]
                     (> (:metric e) boot-threshold)))
                 (with {:event "SystemStart" :group "System"} 
                       (informational "System started less than 2 hours ago" dedup-alert))))

         heartbeat
         (match :service "heartbeat"
                (with {:event "GangliaHeartbeat" :group "Ganglia" :count 2}
                      (splitp < metric
                              90 (critical "No heartbeat from Ganglia agent for at least 90 seconds" dedup-alert)
                              (normal "Heartbeat from Ganglia agent OK" dedup-alert))))

         puppet-last-run
         (match :service "pup_last_run"
                (with {:event "PuppetLastRun" :group "Puppet"}
                      (let [last-run-threshold (- (now) 7200)]
                        (splitp > metric
                                last-run-threshold
                                (switch-epoch-to-elapsed
                                 (major "Puppet agent has not run for at least 2 hours" dedup-alert))
                                (switch-epoch-to-elapsed
                                 (normal "Puppet agent is OK" dedup-alert))))))
         puppet-resource-failed
         (match :service "pup_res_failed"
                (with {:event "PuppetResFailed" :group "Puppet"}
                      (splitp < metric
                              0 (warning "Puppet resources are failing" dedup-alert)
                              (normal "Puppet is updating all resources" dedup-alert))))

         last-gumetric-collection
         (match :service "gu_metric_last"
                (with {:event "GuMgmtMetrics" :group "Ganglia"}
                      (let [last-run-threshold (- (now) 300)]
                        (splitp > metric
                                last-run-threshold
                                (switch-epoch-to-elapsed
                                 (minor "Guardian management status metrics have not been updated for more than 5 minutes" dedup-alert))
                                (switch-epoch-to-elapsed
                                 (normal "Guardian management status metrics are OK" dedup-alert))))))

         fs-util
         (match :service "fs_util"
                (with {:event "FsUtil" :group "OS"}
                      (splitp < metric
                              95 (critical "File system utilisation is very high" dedup-alert)
                              90 (major "File system utilisation is high" dedup-alert)
                              (normal "File system utilisation is OK" dedup-alert))))

         inode-util
         (match :service "inode_util"
                (with {:event "InodeUtil" :group "OS"}
                      (splitp < metric
                              95 (critical "File system inode utilisation is very high" dedup-alert)
                              90 (major "File system inode utilisation is high" dedup-alert)
                              (normal "File system inode utilisation is OK" dedup-alert))))
         swap-util
         (match :service "swap_util"
                (with {:event "SwapUtil" :group "OS"}
                      (splitp < metric
                              90 (minor "Swap utilisation is very high" dedup-alert)
                              (normal "Swap utilisation is OK" dedup-alert))))
         
         cpu-load-five
         (by [:host]
             (match :service "load_five"
                    (lookup-metric "cpu_num"
                                   (split*
                                    (fn [e] (< (* 6 (:cpu_num e)) (:metric e))) (critical "System 5-minute load average is very high" dedup-alert)
                                    (fn [e] (< (* 4 (:cpu_num e)) (:metric e))) (major "System 5-minute load average is high" dedup-alert)
                                    (normal "System 5-minute load average is OK" dedup-alert)))))

         volume-util
         (match :service "df_percent-kb-capacity" ; TODO - in alerta config the split is disjoint
                (with {:event "VolumeUsage" :group "netapp"}
                      (splitp < metric
                              90 (critical "Volume utilisation is very high" dedup-alert)
                              85 (major "Volume utilisation is high" dedup-alert)
                              (normal "Volume utilisation is OK" dedup-alert))))

         r2frontend-http-response-time
         (match :service "gu_requests_timing_time-r2frontend"
                (match :host #"respub"
                       (with {:event "ResponseTime" :group "Web"}
                             (splitp < metric
                                     500 (minor "R2 response time is slow" dedup-4-alert)
                                     (normal "R2 response time is OK" dedup-4-alert)))))

         r2frontend-http-cluster-response-time
         (match :service "gu_requests_timing_time-r2frontend"
                (match :host #"respub"
                       (by :cluster
                           (with {:event "ResponseTime" :group "Web"}
                                 (moving-time-window 30
                                                     (combine riemann.folds/mean
                                                              (adjust set-resource-from-cluster
                                                                      (splitp < metric
                                                                              400 (minor "R2 response time for cluster is slow" dedup-4-alert)
                                                                              (normal "R2 response time for cluster is OK" dedup-4-alert)))))))))

         r2frontend-db-response-time
         (match :service "gu_database_calls_time-r2frontend"
                (with {:event "DbResponseTime" :group "Database"}
                      (splitp < metric
                              30 (minor "R2 database response time is slow" dedup-2-alert)
                              (normal "R2 database response time is OK" dedup-2-alert))))

         ios-purchases-req-drop-off
         (where (and (match :grid "EC2")
                     (match :environment "PROD")
                     (match :service "gu_200_ok_request_status_rate-ios-purchases-api"))
                                        ; We expect at least 100 requests per 15-minute window
                (moving-time-window 900
                                    (combine riemann.folds/sum
                                             (with {:event "RequestRate" :group "Application" :grid "iOSPurchasesAPI"}
                                                   (splitp < metric 100
                                                           (normal "Normal request rate for ios-purchases" dedup-alert)
                                                           (minor "Unusually low request rate for ios-purchases" dedup-alert))))))

         ios-purchases-perc-5xxs
         (where (and (match :grid "EC2")
                     (match :environment "PROD")
                     (match :service #"gu_.*?_request_status_rate-ios-purchases-api"))
                (coalesce
                 (smap (fn [events]
                         (let [service-500s "gu_50x_error_request_status_rate-ios-purchases-api"
                               sum-metrics (fn [events]
                                             (apply + (map :metric events)))
                               total-rate (sum-metrics events)
                               percent (if (== total-rate 0)
                                         0
                                         (* 100
                                            (/ (sum-metrics (filter #(= (:service %) service-500s) events))
                                               total-rate)))
                               new-event {:event "%500s"
                                          :group "Application"
                                          :grid "iOSPurchasesAPI"
                                          :metric percent}]
                           (call-rescue
                            new-event
                            [(cond (< percent 1) (normal "Normal % 500s for ios-purchases" dedup-2-alert)
                                   (< percent 5) (minor "Moderate % 500s for ios-purchases" dedup-2-alert)
                                   :else (major "High % 500s for ios-purchases" dedup-2-alert))]))))))

         discussionapi-http-response-time
         (match :grid "Discussion"
                (match :service "gu_httprequests_application_time-DiscussionApi"
                       (with {:event "ResponseTime" :group "Web"}
                             (by :cluster
                                 (moving-time-window 300
                                                     (combine riemann.folds/mean
                                                              (adjust set-resource-from-cluster
                                                                      (splitp < metric
                                                                              100 (minor "Discussion API cluster response time is slow" dedup-2-alert)
                                                                              (normal "Discussion API cluster response time is OK" dedup-2-alert)))))))))

         content-api-host-item-request-time
         (where* (fn [e] (and (= (:grid e) "EC2")
                              (= (:environment e) "PROD")
                              (= (:service e) "gu_item_http_time-Content-API")))
                 (with {:event "HostItemResponseTime" :group "Application" :grid "ContentAPI"}
                       (by :resource
                           (moving-time-window 300
                                               (combine riemann.folds/mean
                                                        (splitp < metric
                                                                300 (major "Content API host item response time is slow" dedup-alert)
                                                                (normal "Content API host item response time is OK" dedup-alert)))))))

         content-api-host-search-request-time
         (where* (fn [e] (and (= (:grid e) "EC2")
                              (= (:environment e) "PROD")
                              (= (:service e) "gu_search_http_time-Content-API")))
                 (with {:event "HostSearchResponseTime" :group "Application" :grid "ContentAPI"}
                       (by :resource
                           (moving-time-window 300
                                               (combine riemann.folds/mean
                                                        (splitp < metric
                                                                200 (major "Content API host search response time is slow" dedup-alert)
                                                                (normal "Content API host search response time is OK" dedup-alert)))))))

         content-api-request-time
         (where* (fn [e] (and (= (:grid e) "EC2")
                              (= (:environment e) "PROD")
                              (= (:cluster e) "contentapimq_eu-west-1")
                              (= (:service e) "gu_httprequests_application_time-Content-API")))
                 (with {:event "ResponseTime" :group "Application" :grid "ContentAPI"}
                       (by :cluster
                           (moving-time-window 30
                                               (combine riemann.folds/mean
                                                        (adjust set-resource-from-cluster
                                                                (splitp < metric
                                                                        300 (major "Content API MQ cluster response time is slow" dedup-2-alert)
                                                                        (normal "Content API MQ cluster response time is OK" dedup-2-alert))))))))

         content-api-request-rate
         (where* (fn [e] (and (= (:grid e) "EC2")
                              (= (:environment e) "PROD")
                              (= (:cluster e) "contentapimq_eu-west-1")
                              (= (:service e) "gu_httprequests_application_rate-Content-API")))
                 (with {:event "MQRequestRate" :group "Application" :grid "ContentAPI"}
                       (by :cluster
                           (fixed-time-window 15
                                              (combine riemann.folds/sum
                                                       (adjust set-resource-from-cluster
                                                               (splitp < metric
                                                                       70 (normal "Content API MQ total request rate is OK" dedup-2-alert)
                                                                       (major "Content API MQ total request rate is low" dedup-2-alert))))))))]

     (where (not (state "expired"))
            boot-threshold
            heartbeat
            puppet-last-run
            puppet-resource-failed
            ; TODO - GangliaTCPStatus - string based metric
            last-gumetric-collection
            fs-util
            inode-util
            swap-util
            cpu-load-five
            ; TODO - SnapmirrorSync - ask nick what this is doing - seems to be comparing same metric to self
            volume-util
            ; TODO - R2CurrentMode - string based metric
            r2frontend-http-response-time
            r2frontend-http-cluster-response-time
            r2frontend-db-response-time
            ios-purchases-req-drop-off
            ios-purchases-perc-5xxs
            discussionapi-http-response-time
            content-api-host-item-request-time
            content-api-host-search-request-time
            content-api-request-time
            content-api-request-rate)))

            ; TODO - check this - the alerta check seems non-sensical as it uses a static value     
            ; (streams
            ;   (match :grid "Frontend"
            ;     (with {:event "NgnixError" :group "Web"}
            ;        (splitp < metric 0 
            ;                         (major "There are status code 499 client errors")
            ;                         (normal "No status code 499 client errors")))))

  (streams
   (with {:metric 1 :host hostname :state "normal" :service "riemann events_sec"}
         (rate 10 index graph)))

  (streams
   (let [success-service-name #"^gu_200_ok_request_status_rate-frontend-"
         error-service-name "gu_js_diagnostics_rate-frontend-diagnostics"]
     (where (and metric (or (service success-service-name) (service error-service-name)))
            (by [:environment]
                (moving-time-window 60
                                    (smap (fn [events]
                                            (let [service-filter (fn [service-name event] (riemann.common/match service-name (:service event)))
                                                  sum riemann.folds/sum
                                                  total-success (->> events (filter (partial service-filter success-service-name)) sum)
                                                  total-error (->> events (filter (partial service-filter error-service-name)) sum)
                                                  threshold 0.10]
                                              (if (and total-success total-error)
                                                (let [ratio (double (/ (:metric total-error) (:metric total-success)))
                                                      environment (:environment total-success)
                                                      grid (:grid total-success)
                                                      new-event {:host "riemann"
                                                                 :service "frontend_js_error_ratio"
                                                                 :metric ratio
                                                                 :environment environment
                                                                 :group "Frontend"
                                                                 :resource grid
                                                                 :event "JsErrorRate"}]
                                                  (do
                                                    (debug
                                                     (format "%s: Events seen %d; ratio %f; status %s"
                                                             environment
                                                             (count events)
                                                             ratio
                                                             (if (> ratio threshold) "bad" "okay")))
                                                    (if (> ratio threshold)
                                                      (call-rescue new-event [(major "JS error rate unexpectedly high" dedup-4-alert)])
                                                      (call-rescue new-event [(normal "JS error rate within limits" dedup-4-alert)]))))))))))))))
