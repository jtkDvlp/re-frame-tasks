(ns jtk-dvlp.re-frame.tasks
  (:require
   [cljs.core.async]
   [jtk-dvlp.async :as a]
   [re-frame.core :as rf]
   [re-frame.interceptor :as interceptor]))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Functions

(defn register
  "Register task within app state. Also see event `::register`.
   Tasks can be used via subscriptions `::tasks` and `::running?`."
  [db {:keys [::id] :as task}]
  (assoc-in db [::db :tasks id] task))

(defn unregister
  "Unregister task within app state. Also see event `::unregister` and `::unregister-and-dispatch-original`.
   Tasks can be used via subscriptions `::tasks` and `::running?`."
  [db id-or-task]
  (let [id (if (map? id-or-task) (::id id-or-task) id-or-task)]
    (update-in db [::db :tasks] dissoc id)))

(def ^:private !completion-keys-per-effect
  (atom {}))

(def set-completion-keys-per-effect!
  "Sets completion keys per effect."
  (partial reset! !completion-keys-per-effect))

(def merge-completion-keys-per-effect!
  "Merge completion keys per effect."
  (partial swap! !completion-keys-per-effect merge))

(defn- get-completion-keys-for-effect
  [fx]
  (if-let [completion-keys (get @!completion-keys-per-effect fx)]
    completion-keys
    (throw (ex-info (str "No completion keys set for effect '" fx "'") {:code ::no-completion-keys, :effect fx}))))

(defn assoc-original-event
  "Assoc `original-event` to task if `event` is `::unregister-and-dispatch-original` and its original is nil."
  [event original-event]
  (let [[event-name _ maybe-original-event]
        event]

    (cond-> event
      (and
       (= event-name ::unregister-and-dispatch-original)
       (nil? maybe-original-event))
      (assoc 2 original-event))))

(defn update-original-event
  "Update original event of task if `event` is `::unregister-and-dispatch-original`."
  [event f & args]
  (let [[event-name _ _maybe-original-event]
        event]

    (if (= event-name ::unregister-and-dispatch-original)
      (apply update event 2 f args)
      event)))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Interceptors

(defn- fx-handler-run?
  [{:keys [stack]}]
  (->> stack
       (filter #(= :fx-handler (:id %)))
       (seq)))

(defn- normalize-task
  [name-or-task]
  (if (map? name-or-task)
    name-or-task
    {:name name-or-task}))

(defn- normalize-fx
  [effect]
  (let [[effect-key :as effect]
        (cond-> effect
          (not (vector? effect))
          (vector))]

    (cond-> effect
      (= effect-key :fx)
      (conj 1))))

(defonce ^:private !task<->fxs-counters
  (atom {}))

(defn- unregister-by-fx
  [effect completion-keys task]
  (reduce
   (fn [effect completion-key]
     (update effect completion-key (partial vector ::unregister-and-dispatch-original task)))
   effect
   completion-keys))

(def ^:private fx-special?
  (comp (partial = :fx) first))

(defn- unregister-by-fxs
  [{:keys [effects] :as context}
   {:keys [::id] :as task}
   fxs]

  (loop [n 0

         [effect-path & rest-fxs]
         fxs

         {:key [effects] :as context}
         context]

    (if effect-path
      (let [[effect-key effect-data]
            (if (fx-special? effect-path)
              ;; NOTE: butlast damit ich direkt den map-entry nach Vorlage des :fx in der Hand habe,
              ;;       siehe die Vorbereitung in `normalize-fx`.
              (get-in effects (butlast effect-path))
              (find effects (first effect-path)))

            completion-keys
            (get-completion-keys-for-effect effect-key)]

        (->> task
             (unregister-by-fx effect-data completion-keys)
             (assoc-in context (cons :effects effect-path))
             (recur (inc n) rest-fxs)))

      (when (> n 0)
        (swap! !task<->fxs-counters assoc id n)
        context))))

(defn- unregister-by-failed-acofx
  [context task ?acofx]
  (cljs.core.async/take!
   ?acofx
   (fn [result]
     (when (a/exception? result)
       (rf/dispatch [::unregister task]))))
  context)

(defn- get-db
  [context]
  (or
   (interceptor/get-effect context :db)
   (interceptor/get-coeffect context :db)))

(defn- includes-acofxs?
  [context]
  (contains? context :acoeffects))

(defn- handle-acofx-variant
  [{:keys [acoeffects] :as context} task fxs]
  (let [db
        (get-db context)

        {:keys [dispatch-id ?error]}
        acoeffects

        {task-id ::id :as task}
        (assoc task ::id dispatch-id)]

    (if (fx-handler-run? context)
      (or
       (-> context
           (interceptor/assoc-effect :db (update-in db [::db :tasks task-id] merge task))
           (unregister-by-fxs task fxs))
       (interceptor/assoc-effect context :db (unregister db task)))
      (-> context
          (interceptor/assoc-effect :db (register db task))
          (unregister-by-failed-acofx task ?error)))))

(defn- handle-straight-variant
  [context task fxs]
  (let [db
        (get-db context)

        task
        (assoc task ::id (random-uuid))]

    ;; NOTE: no need to register task in every case. the task register
    ;;       would be effectiv too late after finish the handler.
    (if-let [context (unregister-by-fxs context task fxs)]
      (interceptor/assoc-effect context :db (register db task))
      context)))

(defn- get-original-event
  [context]
  (get-in context [:coeffects :original-event]))

(defn- task-by-original-event
  [context]
  (-> context
      (get-original-event)
      (first)))

(defn as-task
  "Creates an interceptor to mark an event as task.
   Give it a name of the task or map with at least a `:name` key or nil / nothing to use the event name.
   Tasks can be used via subscriptions `::tasks` and `::running?`.

   Given vector `fxs` will be used to identify effects to monitor for the task. Can be the keyword of the effect or an vector of effects path (to handle special :fx effect). Completion keys must be set by `set-completion-keys-per-effect!` or `merge-completion-keys-per-effect!` for the effects.

   Within your event handler use `::task` as effect to modify your task data.

   Works in combination with https://github.com/jtkDvlp/re-frame-async-coeffects. For async coeffects there is no need to define what to monitor. Coeffects will be monitored automatically."
  ([]
   (as-task nil))

  ([name-or-task]
   (as-task name-or-task nil))

  ([name-or-task fxs]
   (rf/->interceptor
    :id
    :as-task

    :after
    (fn [context]
      (let [fxs
            (map normalize-fx fxs)

            task
            (-> name-or-task
                (or (task-by-original-event context))
                (normalize-task)
                (assoc :event (get-original-event context))
                (merge (interceptor/get-effect context ::task)))

            ;; NOTE: ::task fx is only to carry task data
            context
            (update context :effects dissoc ::task)]

        (cond
          (includes-acofxs? context)
          (handle-acofx-variant context task fxs)

          :else
          (handle-straight-variant context task fxs)))))))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Events

(rf/reg-event-db ::register
  (fn [db [_ task]]
    (register db task)))

(rf/reg-event-db ::unregister
  (fn [db [_ id-or-task]]
    (unregister db id-or-task)))

(rf/reg-event-fx ::unregister-and-dispatch-original
  (fn [_ [_ task original-event-vec & original-event-args]]
    {::unregister-and-dispatch-original [task original-event-vec original-event-args]}))

(rf/reg-fx ::unregister-and-dispatch-original
  (fn [[{:keys [::id] :as task} original-event-vec original-event-args]]
    (when original-event-vec
      (rf/dispatch (into original-event-vec original-event-args)))

    (if-let [fxs-rest-count (get @!task<->fxs-counters id)]
      (if (= 1 fxs-rest-count)
        (do
          (swap! !task<->fxs-counters dissoc id)
          (rf/dispatch [::unregister task]))
        (swap! !task<->fxs-counters update id dec))
      (rf/dispatch [::unregister task]))))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Subscriptions

(rf/reg-sub ::db
  (fn [{:keys [::db]}]
    db))

(rf/reg-sub ::tasks
  :<- [::db]
  (fn [{:keys [tasks]}]
    (vals tasks)))

(rf/reg-sub ::running?
  :<- [::tasks]
  (fn [tasks [_ name]]
    (-> tasks
        (cond->>
            name (some #(= (:name %) name)))
        (seq)
        (some?))))
