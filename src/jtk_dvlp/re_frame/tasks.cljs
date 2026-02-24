(ns jtk-dvlp.re-frame.tasks
  (:require
   [cljs.core.async]
   [jtk-dvlp.async :as a]
   [re-frame.core :as rf]
   [re-frame.interceptor :as interceptor]))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Functions

(declare get-tasks)

(defn- ->id
  [id-or-task]
  (if (map? id-or-task)
    (::id id-or-task)
    id-or-task))

(defn get-task
  "Gets task in app-db via `id-or-task`. Can return nil."
  [db id-or-task]
  (get-in db [::db :tasks (->id id-or-task)]))

(defn get-task-by-name
  "Gets task in app-db via `name`. Can return nil."
  [db name]
  (->> (get-tasks db)
       (some #(= (:name %) name))))

(defn get-tasks
  "Gets all tasks in app-db. Can return nil"
  [db]
  (vals (get-in db [::db :tasks])))

(defn running?
  "Checks for running task in app-db via `name`."
  [db name]
  (some? (get-task-by-name db name)))

(defn- reset-after-events
  [db id-or-task events]
  (assoc-in db [::db :tasks (->id id-or-task) ::after-events] (vec events)))

(defn attach-after-event
  "Attaches events called after task completion."
  [db id-or-task event]
  (update-in db [::db :tasks (->id id-or-task) ::after-events] (fnil conj []) event))

(defn register
  "Register task within app state. Also see event `::register`.
   Tasks can be used via subscriptions `::tasks` and `::running?`."
  [db {:keys [::id] :as task}]
  (assoc-in db [::db :tasks id] task))

(defn unregister
  "Unregister task within app state. Also see event `::unregister` and `::unregister-and-dispatch-original`.
   Tasks can be used via subscriptions `::tasks` and `::running?`."
  [db id-or-task]
  (update-in db [::db :tasks] dissoc (->id id-or-task)))

(def ^:private !completion-keys-per-effect
  (atom {}))

(def set-completion-keys-per-effect!
  "Sets completion keys per effect via map `{:effect #{:completion-keys,,,}}`."
  (partial reset! !completion-keys-per-effect))

(def merge-completion-keys-per-effect!
  "Merge completion keys per effect via map `{:effect #{:completion-keys,,,}}`."
  (partial swap! !completion-keys-per-effect merge))

(defn add-completion-keys-for-effect!
  "Adds completion keys for effect."
  [effect-key & completion-keys]
  (swap! !completion-keys-per-effect assoc effect-key (set completion-keys)))

(defn- get-completion-keys-for-effect
  [fx]
  (if-let [completion-keys (get @!completion-keys-per-effect fx)]
    completion-keys
    (throw (ex-info (str "No completion keys set for effect '" fx "'") {:code ::no-completion-keys, :effect fx}))))

(defn task-event?
  "Check if event is task based, alias `::unregister-and-dispatch-original`"
  [event]
  (let [[event-name _ _maybe-original-event]
        event]

    (= event-name ::unregister-and-dispatch-original)))

(defn get-original-event
  "Get original event of task event or `event` itself."
  [event]
  (let [[_event-name _ maybe-original-event]
        event]

    (if (task-event? event)
      maybe-original-event
      event)))

(defn some-original-event?
  "Checks for some original event of task or `event` itself."
  [event]
  (-> event
      (get-original-event)
      (some?)))

(defn assoc-orignal-event
  "Assocs `original-event` within maybe task `event`, returns maybe modified `event`."
  [event original-event]
  (if (task-event? event)
    (assoc event 2 original-event)
    event))

(defn update-original-event
  "Updates original event of maybe task `event`, returns maybe modified `event`."
  [event f & args]
  (if (task-event? event)
    (apply update event 2 f args)
    event))

(defn ensure-original-event
  "Ensures `original-event` for direct use or with task."
  [event original-event]
  (if (some-original-event? event)
    event
    (if (task-event? event)
      (assoc-orignal-event event original-event)
      original-event)))


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

(defn- get-effect-by-path
  [{:keys [effects] :as _context} effect-path]
  (if (fx-special? effect-path)
    ;; NOTE: butlast damit ich direkt den map-entry nach Vorlage des :fx in der Hand habe,
    ;;       siehe die Vorbereitung in `normalize-fx`.
    (get-in effects (butlast effect-path))
    (find effects (first effect-path))))

(defn- unregister-by-fxs
  [context {:keys [::id] :as task} fxs]
  (loop [applied-fxs-counter
         0

         [effect-path & rest-fxs]
         fxs

         context
         context]

    (if effect-path
      (if-let [[effect-key effect-data] (get-effect-by-path context effect-path)]
        (let [completion-keys
              (get-completion-keys-for-effect effect-key)]

          (->> task
               (unregister-by-fx effect-data completion-keys)
               (assoc-in context (cons :effects effect-path))
               (recur (inc applied-fxs-counter) rest-fxs)))

        (recur applied-fxs-counter rest-fxs context))

      (when (> applied-fxs-counter 0)
        (swap! !task<->fxs-counters assoc id applied-fxs-counter)
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

(defn- update-db
  [context f & args]
  (let [db (get-db context)]
    (interceptor/assoc-effect context :db (apply f db args))))

(defn- contains-acofxs?
  [context]
  (contains? context :acoeffects))

(defn- handle-acofx-variant
  [{:keys [acoeffects] :as context} task fxs]
  (let [db
        (get-db context)

        {:keys [dispatch-id ?error]}
        acoeffects

        {task-id ::id :as task}
        (merge
         (get-task db dispatch-id)
         (assoc task ::id dispatch-id))]

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

(defn- get-calling-event
  [context]
  (get-in context [:coeffects :original-event]))

(defn- task-by-original-event
  [context]
  (-> context
      (get-calling-event)
      (first)))

(declare while-task)

(defn as-task
  "Creates an interceptor to mark an event as task.
   Give it a name of the task or map with at least a `:name` key or nil / nothing to use the event name.
   Tasks can be used via subscriptions `::tasks` and `::running?`.

   Given vector `fxs` will be used to identify effects to monitor for the task. Can be the keyword of the effect or an vector of effects path (to handle special :fx effect). Completion keys must be set by `set-completion-keys-per-effect!` or `merge-completion-keys-per-effect!` for the effects.

   Given `while-task` action `while-itself` to inject `while-task` with its task to interceptors.

   Within your event handler use `::task` as effect to modify your task data.

   Works in combination with https://github.com/jtkDvlp/re-frame-async-coeffects. For async coeffects there is no need to define what to monitor. Coeffects will be monitored automatically."
  ([]
   (as-task nil))

  ([name-or-task]
   (as-task name-or-task nil))

  ([name-or-task fxs]
   (as-task name-or-task fxs nil))

  ([name-or-task fxs while-itself]
   (rf/->interceptor
    :id
    :as-task

    :after
    (fn [context]
      (let [fxs
            (map normalize-fx fxs)

            {task-name :name :as task}
            (-> name-or-task
                (or (task-by-original-event context))
                (normalize-task)
                (assoc :event (get-calling-event context))
                (merge (interceptor/get-effect context ::task)))

            handle
            (if (contains-acofxs? context)
              handle-acofx-variant
              handle-straight-variant)]

        (cond-> context
          ;; NOTE: ::task fx is only to carry task data
          :always
          (update :effects dissoc ::task)

          :always
          (handle task fxs)

          (some? while-itself)
          (update :queue conj (while-task while-itself [task-name]))))))))

(defn- abort-calling-event
  [context]
  (-> context
      (update :queue empty)
      (update :stack rest)))

(defn- cancel-event
  [context tasks event]
  (.debug js/console "cancel event" (clj->js {:tasks tasks, :event event}))
  (abort-calling-event context))

(defn- delay-event
  [context [task :as tasks] [event-name :as event]]
  (.debug js/console "delay event" (clj->js {:tasks tasks, :event event}))
  (let [db
        (get-db context)

        {:keys [::after-events]}
        (get-task db task)

        get-event-name
        first

        after-events
        (->> after-events
             (remove #(= (get-event-name %) event-name))
             (vec)
             (#(conj % event)))]

    (-> context
        (update-db reset-after-events task after-events)
        (abort-calling-event))))

(defn- queue-event
  [context [task :as tasks] event]
  (.debug js/console "queue event" (clj->js {:tasks tasks, :event event}))
  (-> context
      (update-db attach-after-event task event)
      (abort-calling-event)))

(defn while-task
  "Creates an interceptor to control event execution during running tasks.

   Choose via `action` between `:cancel` to cancel the event, `:delay` to delay
   execution till task completion (last event call will take place, precendent
   calls will be ignored) or `:queue` to queue executions till task completion
   (considers call order).

   Applicates for every task or selected `tasks` via their names.

   Can be injected multiple times, consider injection order.
   Can be used as global interceptor, consider there is no reversal allow / pass
   functionality."
  ([action]
   (let [all-tasks identity]
     (while-task action all-tasks)))

  ([action tasks]
   (let [action-fn
         (case action
           :cancel cancel-event
           :delay delay-event
           :queue queue-event
           action)

         blocking-tasks
         (cond
           (fn? tasks)
           tasks

           (coll? tasks)
           #(filter (comp (partial contains? (set tasks)) :name) %)

           :else
           #(filter (comp (partial = tasks) :name) %))]

     (rf/->interceptor
      :id
      :while-task

      :before
      (fn [context]
        (let [blocking-tasks
              (-> context
                  (get-db)
                  (get-tasks)
                  (blocking-tasks))

              blocking-tasks?
              (not (empty? blocking-tasks))

              [event-name :as event]
              (get-calling-event context)

              pass-events
              #{::unregister
                ::unregister-and-dispatch-original}

              pass-event?
              (contains? pass-events event-name)]

          (cond
            pass-event?
            context

            blocking-tasks?
            (action-fn context blocking-tasks event)

            :else context)))))))

;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Events

(rf/reg-event-db ::register
  (fn [db [_ task]]
    (register db task)))

(rf/reg-event-fx ::unregister
  (fn [{:keys [db]} [_ id-or-task]]
    (let [{:keys [::id ::after-events]}
          (get-task db id-or-task)]

      {:db
       (unregister db id)

       :dispatch-n
       (vec after-events)})))

(rf/reg-event-fx ::unregister-and-dispatch-original
  (fn [_ [_ task original-event & original-event-args]]
    {::unregister-and-dispatch-original [task original-event original-event-args]}))

(rf/reg-fx ::unregister-and-dispatch-original
  (fn [[{:keys [::id] :as task} original-event original-event-args]]
    (when original-event
      (rf/dispatch (into original-event original-event-args)))

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
    (cond->> tasks
      name
      (some #(= (:name %) name))

      :always
      (some?))))
