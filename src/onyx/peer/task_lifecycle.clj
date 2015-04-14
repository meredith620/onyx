(ns ^:no-doc onyx.peer.task-lifecycle
    (:require [clojure.core.async :refer [alts!! <!! >!! <! >! timeout chan close! thread go dropping-buffer]]
              [com.stuartsierra.component :as component]
              [dire.core :as dire]
              [taoensso.timbre :refer [info warn trace fatal] :as timbre]
              [onyx.log.commands.common :as common]
              [onyx.log.entry :as entry]
              [onyx.static.planning :refer [find-task build-pred-fn]]
              [onyx.messaging.acking-daemon :as acker]
              [onyx.peer.task-lifecycle-extensions :as l-ext]
              [onyx.peer.pipeline-extensions :as p-ext]
              [onyx.peer.function :as function]
              [onyx.peer.operation :as operation]
              [onyx.extensions :as extensions]
              [onyx.compression.nippy])
    (:import [java.security MessageDigest]
             [uk.co.real_logic.aeron.exceptions.DriverTimeoutException]))

;; TODO: Might want to allow a peer to reboot from an
;; exception without killing the job, e.g. transient
;; connection failure to ZooKeeper.
(def restartable-exceptions [uk.co.real_logic.aeron.exceptions.DriverTimeoutException])

(defn at-least-one-active? [replica peers]
  (->> peers
       (map #(get-in replica [:peer-state %]))
       (filter (partial = :active))
       (seq)))

(defn job-covered? [replica job]
  (let [tasks (get-in replica [:tasks job])
        active? (partial at-least-one-active? replica)]
    (every? identity (map #(active? (get-in replica [:allocations job %])) tasks))))

(defn resolve-calling-params [catalog-entry opts]
  (concat (get (:onyx.peer/fn-params opts) (:onyx/name catalog-entry))
          (map (fn [param] (get catalog-entry param)) (:onyx/params catalog-entry))))

(defn munge-start-lifecycle [event]
  (l-ext/start-lifecycle?* event))

(defn add-acker-id [event m]
  (let [peers (get-in @(:onyx.core/replica event) [:ackers (:onyx.core/job-id event)])
        n (mod (hash (:message m)) (count peers))]
    (assoc m :acker-id (nth peers n))))

(defn add-completion-id [event m]
  (assoc m :completion-id (:onyx.core/id event)))

(defn sentinel-found? [event]
  (seq (filter (partial = :done) (map :message (:onyx.core/batch event)))))

(defn complete-job [{:keys [onyx.core/job-id onyx.core/task-id] :as event}]
  (let [entry (entry/create-log-entry :exhaust-input {:job job-id :task task-id})]
    (>!! (:onyx.core/outbox-ch event) entry)))

(defn sentinel-id [event]
  (:id (first (filter #(= :done (:message %)) (:onyx.core/batch event)))))

(defn drop-nth [n coll]
  (keep-indexed #(if (not= %1 n) %2) coll))

(defn fuse-ack-vals [task parent-ack child-ack]
  (if (= (:onyx/type task) :output)
    parent-ack
    (acker/prefuse-vals (vector parent-ack child-ack))))

(defn join-output-paths [all to-add downstream]
  (cond (= to-add :all) (into #{} downstream)
        (= to-add :none) #{}
        :else (clojure.set/union (into #{} all) (into #{} to-add))))

(defn choose-output-paths
  [event compiled-flow-conditions result leaf serialized-task downstream]
  (if (seq compiled-flow-conditions)
    (reduce
     (fn [{:keys [flow exclusions] :as all} entry]
       (if ((:flow/predicate entry) [event (:message (:root result)) (:message leaf) (map :message (:leaves result))])
         (if (:flow/short-circuit? entry)
           (reduced {:flow (join-output-paths flow (:flow/to entry) downstream)
                     :exclusions (clojure.set/union (into #{} exclusions) (into #{} (:flow/exclude-keys entry)))
                     :post-transformation (:flow/post-transform entry)
                     :action (:flow/action entry)})
           {:flow (join-output-paths flow (:flow/to entry) downstream)
            :exclusions (clojure.set/union (into #{} exclusions) (into #{} (:flow/exclude-keys entry)))})
         all))
     {:flow #{} :exclusions #{}}
     compiled-flow-conditions)
    {:flow downstream}))

(defn add-route-data
  [{:keys [onyx.core/serialized-task onyx.core/compiled-norm-fcs onyx.core/compiled-ex-fcs]
    :as event} result leaf downstream]
  (if (operation/exception? (:message leaf))
    (choose-output-paths event compiled-ex-fcs result leaf serialized-task downstream)
    (choose-output-paths event compiled-norm-fcs result leaf serialized-task downstream)))

(defn route-output-flow
  [{:keys [onyx.core/serialized-task onyx.core/results] :as event}]
  (let [downstream (keys (:egress-ids serialized-task))]
    (merge
     event
     {:onyx.core/results
      (mapv
       (fn [{:keys [root leaves] :as result}]
         (assoc result :leaves
                (mapv
                 (fn [leaf]
                   (assoc leaf :routes (add-route-data event result leaf downstream)))
                 leaves)))
       results)})))

(defn hash-value [x]
  (let [md5 (MessageDigest/getInstance "MD5")]
    (apply str (.digest md5 (.getBytes (pr-str x) "UTF-8")))))

(defn group-message [segment catalog task]
  (let [t (find-task catalog task)]
    (if-let [k (:onyx/group-by-key t)]
      (hash-value (get segment k))
      (when-let [f (:onyx/group-by-fn t)]
        (hash-value ((operation/resolve-fn {:onyx/fn f}) segment))))))

(defn group-segments [next-tasks catalog result event]
  (assoc result
    :leaves
    (mapv
     (fn [leaf]
       (let [msg (if (and (operation/exception? (:message leaf))
                          (:post-transformation (:routes leaf)))
                   (operation/apply-function
                    (operation/kw->fn (:post-transformation (:routes leaf)))
                    [event] (:message leaf))
                   (:message leaf))]
         (assoc leaf
           :hash-group
           (reduce (fn [groups t]
                     (assoc groups t (group-message msg catalog t)))
                   {} next-tasks)
           :message (apply dissoc msg (:exclusions (:routes leaf))))))
     (:leaves result))))

(defn build-new-segments
  [{:keys [onyx.core/results onyx.core/serialized-task onyx.core/catalog] :as event}]
  (let [next-tasks (keys (:egress-ids serialized-task))
        results (mapv
                 (fn [{:keys [root leaves] :as result}]
                   (assoc result :leaves
                          (mapv
                           (fn [leaf]
                             (merge leaf
                                    {:id (:id root)
                                     :acker-id (:acker-id root)
                                     :completion-id (:completion-id root)
                                     :ack-vals (repeatedly (count (:flow (:routes leaf)))
                                                           (fn [] (acker/gen-ack-value)))}))
                           leaves)))
                 results)]
    (merge event {:onyx.core/results (map #(group-segments next-tasks catalog % event) results)})))

(defn gen-ack-fusion-vals [task-map leaves]
  (when-not (= (:onyx/type task-map) :output)
    (mapcat :ack-vals (remove (fn [leaf] (= (:action (:routes leaf)) :retry)) leaves))))

(defn ack-messages [{:keys [onyx.core/results onyx.core/task-map] :as event}]
  (when (not (:onyx/side-effects-only? (:onyx.core/task-map event)))
    (doseq [result results]
      (let [leaves (filter (fn [leaf] (seq (:flow (:routes leaf)))) (:leaves result))
            leaf-vals (gen-ack-fusion-vals task-map leaves)
            fused-vals (acker/prefuse-vals (conj leaf-vals (:ack-val (:root result))))
            link (operation/peer-link event (:acker-id (:root result)))]
        (extensions/internal-ack-message
         (:onyx.core/messenger event)
         event
         link
         (:id (:root result))
         (:completion-id (:root result))
         ;; or'ing by zero covers the case of flow conditions where an
         ;; input task produces a segment that goes nowhere.
         (or fused-vals 0)))))
  event)

(defn retry-messages [{:keys [onyx.core/results] :as event}]
  (doseq [result results]
    (when (seq (filter (fn [leaf] (= :retry (:action (:routes leaf)))) (:leaves result)))
      (let [link (operation/peer-link event (:completion-id (:root result)))]
        (extensions/internal-retry-message
         (:onyx.core/messenger event)
         event
         (:id (:root result))
         link))))
  event)

(defn inject-batch-resources [event]
  (let [cycle-params {:onyx.core/lifecycle-id (java.util.UUID/randomUUID)}]
    (merge event cycle-params (l-ext/inject-batch-resources* event))))

(defn read-batch [event]
  (let [rets (p-ext/read-batch event)]
    (when (and (= (count (:onyx.core/batch rets)) 1)
               (= (:message (first (:onyx.core/batch rets))) :done))
      (Thread/sleep (:onyx.core/drained-back-off event)))
    (merge event rets)))

(defn tag-messages [event]
  (merge
   event
   (when (= (:onyx/type (:onyx.core/task-map event)) :input)
     (update-in
      event
      [:onyx.core/batch]
      (fn [batch]
        (map (comp (partial add-completion-id event)
                   (partial add-acker-id event))
             batch))))))

(defn add-messages-to-timeout-pool [{:keys [onyx.core/state] :as event}]
  (when (= (:onyx/type (:onyx.core/task-map event)) :input)
    (swap! state update-in [:timeout-pool 0] concat (map :id (:onyx.core/batch event))))
  event)

(defn try-complete-job [event]
  (when (sentinel-found? event)
    (if (p-ext/drained? event)
      (complete-job event)
      (p-ext/retry-message event (sentinel-id event))))
  event)

(defn strip-sentinel
  [{:keys [onyx.core/batch onyx.core/message-tree] :as event}]
  (merge
   event
   (when-let [k (.indexOf (map :message batch) :done)]
     {:onyx.core/batch (drop-nth k batch)})))

(defn collect-next-segments [event input]
  (let [segments (try (function/apply-fn event input) (catch Exception e e))]
    (if (sequential? segments) segments (vector segments))))

(defn apply-fn-single [{:keys [onyx.core/batch] :as event}]
  ;; PERF: Tight inner loop where a lot of time is spent
  (merge
   event
   {:onyx.core/results
    (reduce
     (fn [coll segment]
       (let [segments (collect-next-segments event (:message segment))
             leaves (map (partial apply hash-map) (map vector (repeat :message) segments))]
         (conj coll {:root segment :leaves leaves})))
     []
     batch)}))

(defn apply-fn-batch [{:keys [onyx.core/batch] :as event}]
  ;; Batched functions intentionally ignore their outputs.
  (let [segments (map :message batch)]
    (function/apply-fn event segments)
    (merge
     event
     {:onyx.core/results
      (reduce
       (fn [coll segment]
         (let [leaves (map (partial apply hash-map) (map vector (repeat :message) segments))]
           (conj coll {:root segment :leaves leaves})))
       []
       batch)})))

(defn apply-fn [event]
  (if (:onyx/side-effects-only? (:onyx.core/task-map event))
    (apply-fn-batch event)
    (apply-fn-single event)))

(defn write-batch [event]
  (merge event (p-ext/write-batch event)))

(defn close-batch-resources [event]
  (merge event (l-ext/close-batch-resources* event)))

(defn launch-aux-threads!
  [messenger event outbox-ch seal-ch completion-ch task-kill-ch]
  (thread
   (try
     (loop []
       (when-let [[v ch] (alts!! [task-kill-ch
                                  completion-ch
                                  seal-ch
                                  (:release-ch messenger)
                                  (:retry-ch messenger)]
                                 :priority true)]
         (when-not (= ch task-kill-ch)
           (cond (= ch (:release-ch messenger))
                 (p-ext/ack-message event v)

                 (= ch (:retry-ch messenger))
                 (p-ext/retry-message event v)

                 (= ch completion-ch)
                 (let [{:keys [id peer-id]} v]
                   (let [peer-link (operation/peer-link event peer-id)]
                     (extensions/internal-complete-message (:onyx.core/messenger event) event id peer-link)))

                 (= ch seal-ch)
                 (do
                   (p-ext/seal-resource event)
                   (let [entry (entry/create-log-entry :seal-output {:job (:onyx.core/job-id event)
                                                                     :task (:onyx.core/task-id event)})]
                     (>!! outbox-ch entry))))
           (recur))))
     (catch Exception e
       (fatal e)))))

(defn replay-messages! [messenger event replay-interval task-kill-ch]
  (go
   (when (= :input (:onyx/type (:onyx.core/task-map event)))
     (loop []
       (let [timeout-ch (timeout replay-interval)
             ch (second (alts!! [timeout-ch task-kill-ch]))]
         (when (= ch timeout-ch)
           (let [tail (last (get-in @(:onyx.core/state event) [:timeout-pool]))]
             (doseq [m tail]
               (when (p-ext/pending? event m)
                 (taoensso.timbre/info (str "Message " m " timed out, replaying it from it's initial task."))
                 (p-ext/retry-message event m)))
             (swap! (:onyx.core/state event) update-in [:timeout-pool] (comp vec #(conj % []) butlast))
             (recur))))))))

(defn handle-exception [e restart-ch outbox-ch job-id]
  (warn e)
  (if (some #{(type e)} restartable-exceptions)
    (>!! restart-ch true)
    (let [entry (entry/create-log-entry :kill-job {:job job-id})]
      (>!! outbox-ch entry))))

(defn only-relevant-branches [flow task]
  (filter #(= (:flow/from %) task) flow))

(defn compile-flow-conditions [flow-conditions task-name f]
  (let [conditions (filter f (only-relevant-branches flow-conditions task-name))]
    (map
     (fn [condition]
       (assoc condition :flow/predicate (build-pred-fn (:flow/predicate condition) condition)))
     conditions)))

(defn compile-fc-norms [flow-conditions task-name]
  (compile-flow-conditions flow-conditions task-name (comp not :flow/thrown-exception?)))

(defn compile-fc-exs [flow-conditions task-name]
  (compile-flow-conditions flow-conditions task-name :flow/thrown-exception?))

(defn run-task-lifecycle [init-event seal-ch kill-ch ex-f]
  (try
    (while (first (alts!! [seal-ch kill-ch] :default true))
      (-> init-event
          (inject-batch-resources)
          (read-batch)
          (tag-messages)
          (add-messages-to-timeout-pool)
          (try-complete-job)
          (strip-sentinel)
          (apply-fn)
          (route-output-flow)
          (build-new-segments)
          (write-batch)
          (ack-messages)
          (retry-messages)
          (close-batch-resources)))
    (catch Exception e
      (ex-f e))))

(defn resolve-compression-fn-impls [opts]
  (assoc opts
    :onyx.peer/decompress-fn-impl
    (if-let [f (:onyx.peer/decompress-fn opts)]
      (operation/resolve-fn f)
      onyx.compression.nippy/decompress)
    :onyx.peer/compress-fn-impl
    (if-let [f (:onyx.peer/compress-fn opts)]
      (operation/resolve-fn f)
      onyx.compression.nippy/compress)))

(defn any-ackers? [replica job-id]
  (> (count (get-in replica [:ackers job-id])) 0))

(defrecord TaskLifeCycle
    [id log messenger-buffer messenger job-id task-id replica restart-ch
     kill-ch outbox-ch seal-resp-ch completion-ch opts task-kill-ch]
  component/Lifecycle

  (start [component]
    (try
      (let [catalog (extensions/read-chunk log :catalog job-id)
            task (extensions/read-chunk log :task task-id)
            flow-conditions (extensions/read-chunk log :flow-conditions job-id)
            catalog-entry (find-task catalog (:name task))
            ;; Number of buckets in the timeout pool is covered over a 60 second
            ;; interval, moving each bucket back 60 seconds / N buckets
            replay-interval (or (:onyx/replay-interval catalog-entry) 1000)
            pending-timeout (or (:onyx/pending-timeout catalog-entry) 60000)
            n-buckets (int (Math/ceil (/ pending-timeout replay-interval)))
            buckets (vec (repeat n-buckets []))

            _ (taoensso.timbre/info (format "[%s] Starting Task LifeCycle for job %s, task %s" id job-id (:name task)))

            pipeline-data {:onyx.core/id id
                           :onyx.core/job-id job-id
                           :onyx.core/task-id task-id
                           :onyx.core/task (:name task)
                           :onyx.core/catalog catalog
                           :onyx.core/workflow (extensions/read-chunk log :workflow job-id)
                           :onyx.core/flow-conditions flow-conditions
                           :onyx.core/compiled-norm-fcs (compile-fc-norms flow-conditions (:name task))
                           :onyx.core/compiled-ex-fcs (compile-fc-exs flow-conditions (:name task))
                           :onyx.core/task-map catalog-entry
                           :onyx.core/serialized-task task
                           :onyx.core/params (resolve-calling-params catalog-entry opts)
                           :onyx.core/drained-back-off (or (:onyx.peer/drained-back-off opts) 400)
                           :onyx.core/log log
                           :onyx.core/messenger-buffer messenger-buffer
                           :onyx.core/messenger messenger
                           :onyx.core/outbox-ch outbox-ch
                           :onyx.core/seal-ch seal-resp-ch
                           :onyx.core/peer-opts (resolve-compression-fn-impls opts)
                           :onyx.core/replica replica
                           :onyx.core/state (atom {:timeout-pool buckets})}

            ex-f (fn [e] (handle-exception e restart-ch outbox-ch job-id))
            pipeline-data (merge pipeline-data (l-ext/inject-lifecycle-resources* pipeline-data))]

        (while (and (first (alts!! [kill-ch task-kill-ch] :default true))
                    (not (:onyx.core/start-lifecycle? (munge-start-lifecycle pipeline-data))))
          (Thread/sleep (or (:onyx.peer/sequential-back-off opts) 2000)))

        (>!! outbox-ch (entry/create-log-entry :signal-ready {:id id}))

        (loop [replica-state @replica]
          (when (and (first (alts!! [kill-ch task-kill-ch] :default true))
                     (or (not (job-covered? replica-state job-id))
                         (not (any-ackers? replica-state job-id))))
            (taoensso.timbre/info (format "[%s] Not enough virtual peers have warmed up to start the job yet, backing off and trying again..." id))
            (Thread/sleep 500)
            (recur @replica)))

        (let [replay-messages-ch (replay-messages! messenger pipeline-data replay-interval task-kill-ch)
              aux-ch (launch-aux-threads! messenger pipeline-data outbox-ch seal-resp-ch completion-ch task-kill-ch)
              task-lifecycle-ch (thread (run-task-lifecycle pipeline-data seal-resp-ch kill-ch ex-f))]
          (assoc component 
            :pipeline-data pipeline-data
            :seal-ch seal-resp-ch
            :task-lifecycle-ch task-lifecycle-ch
            :replay-messages-ch replay-messages-ch
            :aux-ch aux-ch)))
      (catch Exception e
        (handle-exception e restart-ch outbox-ch job-id)
        component)))

  (stop [component]
    (taoensso.timbre/info (format "[%s] Stopping Task LifeCycle for %s" id (:onyx.core/task (:pipeline-data component))))
    (when-let [event (:pipeline-data component)]
      (l-ext/close-lifecycle-resources* event)

      (close! (:seal-ch component))
      (close! (:task-lifecycle-ch component))
      
      ;; Ensure task operations are finished before closing peer connections
      (<!! (:task-lifecycle-ch component))
      (<!! (:replay-messages-ch component))
      (<!! (:aux-ch component))
      (<!! (:seal-ch component))

      (let [state @(:onyx.core/state event)]
        (doseq [[_ link] (:links state)]
          (extensions/close-peer-connection (:onyx.core/messenger event) event link))))

    (assoc component
      :pipeline-data nil
      :seal-ch nil
      :aux-ch nil
      :replay-messages-ch nil
      :task-lifecycle-ch nil
      :task-lifecycle-ch nil)))

(defn task-lifecycle [args {:keys [id log messenger-buffer messenger job task replica
                                   restart-ch kill-ch outbox-ch seal-ch completion-ch opts task-kill-ch]}]
  (map->TaskLifeCycle {:id id :log log :messenger-buffer messenger-buffer
                       :messenger messenger :job-id job :task-id task :restart-ch restart-ch
                       :kill-ch kill-ch :outbox-ch outbox-ch
                       :replica replica :seal-resp-ch seal-ch :completion-ch completion-ch
                       :opts opts :task-kill-ch task-kill-ch}))

(dire/with-post-hook! #'munge-start-lifecycle
  (fn [{:keys [onyx.core/id onyx.core/lifecycle-id onyx.core/start-lifecycle?] :as event}]
    (when-not start-lifecycle?
      (timbre/info (format "[%s / %s] Lifecycle chose not to start the task yet. Backing off and retrying..." id lifecycle-id)))))

(dire/with-post-hook! #'inject-batch-resources
  (fn [{:keys [onyx.core/id onyx.core/lifecycle-id]}]
    (taoensso.timbre/trace (format "[%s / %s] Created new tx session" id lifecycle-id))))

(dire/with-post-hook! #'read-batch
  (fn [{:keys [onyx.core/id onyx.core/batch onyx.core/lifecycle-id]}]
    (taoensso.timbre/info (format "[%s / %s] Read %s segments" id lifecycle-id (count batch)))))

(dire/with-post-hook! #'apply-fn
  (fn [{:keys [onyx.core/id onyx.core/results onyx.core/lifecycle-id]}]
    (taoensso.timbre/trace (format "[%s / %s] Applied fn to %s segments" id lifecycle-id (count results)))))

(dire/with-post-hook! #'write-batch
  (fn [{:keys [onyx.core/id onyx.core/lifecycle-id onyx.core/results]}]
    (taoensso.timbre/info (format "[%s / %s] Wrote %s segments" id lifecycle-id (count results)))))

(dire/with-post-hook! #'close-batch-resources
  (fn [{:keys [onyx.core/id onyx.core/lifecycle-id]}]
    (taoensso.timbre/trace (format "[%s / %s] Closed batch plugin resources" id lifecycle-id))))
