(ns jtk-dvlp.re-frame.tasks
  (:require
   [taoensso.timbre :as log]

   [re-frame.core :as rf]
   [re-frame.interceptor :as interceptor]))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers: Original Event

(defn- -get-original-event
  [context]
  (get-in context [:coeffects :original-event]))

(defn- abort-original-event
  [context]
  (log/trace "aborting original event" context)
  (update context :queue empty))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers: DB Effect

(defn- get-app-db
  [context]
  (or
   (interceptor/get-effect context :db)
   (interceptor/get-coeffect context :db)))

(defn- update-app-db
  [context f & args]
  (let [db (get-app-db context)]
    (interceptor/assoc-effect context :db (apply f db args))))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers: Effects

(def ^:private fx-special?
  (partial = :fx))

(defn- normalize-effect-key
  [effect-key]
  (if (vector? effect-key)
    effect-key (vector effect-key)))

(defn- get-effect
  [context effect-key]
  (let [[effect-key effect-index]
        (normalize-effect-key effect-key)]

    (cond-> (interceptor/get-effect context effect-key)
      (fx-special? effect-key)
      (get effect-index))))

(defn- contains-effect?
  [context effect-key]
  (-> context
      (get-effect effect-key)
      (some?)))

(defn- update-effect
  [context effect-key f & args]
  (let [[effect-key effect-index]
        (normalize-effect-key effect-key)]

    (interceptor/update-effect
     context effect-key
     (fn [effect]
       (if (fx-special? effect-key)
         (apply update effect effect-index f args)
         (apply f effect args))))))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Debounce

(defn- create-timeout!
  [f ms]
  (log/trace "creating timeout" {:f f, :ms ms})
  {:ms ms, :f f, :t (js/setTimeout f ms)})

(defn- cancel-timeout!
  [timeout]
  (log/trace "canceling timeout" timeout)
  (js/clearTimeout (:t timeout))
  nil)

(defn- flush-timeout!
  [timeout]
  (log/trace "flushing timeout" timeout)
  (js/clearTimeout (:t timeout))
  ((:f timeout))
  nil)

(defonce ^:private !debounce-timeouts
  (atom {}))

(defn dispatch-debounce
  [{:keys [ms] [event :as dispatch] :dispatch :as args}]
  (log/trace "dispatching debounced" args)
  (letfn [(dispatch! []
            (swap! !debounce-timeouts dissoc event)
            (rf/dispatch (vary-meta dispatch assoc ::debounce {:flush? true})))]

    (when-let [timeout (get @!debounce-timeouts event)]
      (cancel-timeout! timeout))

    (let [timeout (create-timeout! dispatch! ms)]
      (swap! !debounce-timeouts assoc event timeout))))

(def ^{:rf/reg-fx ::dispatch-debounce} dispatch-debounce-fx
  "re-frame effect to debounce `dispatch` within `ms`."
  (rf/reg-fx ::dispatch-debounce dispatch-debounce))

(defn flush-debounce
  [{[event] :dispatch :as args}]
  (log/trace "flushing debounce" args)
  (when-let [timeout (get @!debounce-timeouts event)]
    (flush-timeout! timeout)))

(def ^{:rf/reg-fx ::flush-debounce} flush-debounce-fx
  "re-frame effect to flush debounced `dispatch`."
  (rf/reg-fx ::flush-debounce flush-debounce))

(defn debounce
  "Creates an interceptor to debounce event calls within `ms`.

   To not debounce but call event add event vector meta `::debounce {:flush? true}`."
  ([ms]
   (rf/->interceptor
    :id ::debounce

    :before
    (fn [context]
      (log/trace "debouncing event" {:context context, :ms ms})
      (let [original-event
            (-get-original-event context)

            flush?
            (-> original-event
                (meta)
                (::debounce)
                (:flush?))]

        (if flush?
          context
          (do
            (dispatch-debounce {:ms ms, :dispatch original-event})
            (abort-original-event context))))))))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tasks

(declare get-tasks)

(defn- ->id
  [id-or-task]
  (if (map? id-or-task)
    (::id id-or-task)
    id-or-task))

(def ^{:rf/reg-sub ::db} db-sub
  "re-frame subscription for ns db."
  (rf/reg-sub ::db
    :-> ::db))

(defn get-task
  "Gets task in app-db via `id-or-task`. Can return nil."
  [db id-or-task]
  (get-in db [::db :tasks (->id id-or-task)]))

(defn get-task-by-name
  "Gets task in app-db via `name`. Can return nil."
  [db name]
  ;; WATCHOUT: `some` yields the predicate's result, so a plain
  ;; `#(= (:name %) name)` returns `true` rather than the task.
  (->> (get-tasks db)
       (filter #(= (:name %) name))
       (first)))

(defn get-tasks
  "Gets all tasks in app-db. Can return nil. Also see subscription `::tasks`."
  [db]
  (vals (get-in db [::db :tasks])))

(def ^{:rf/reg-sub ::tasks} tasks-sub
  "re-frame subscription for all tasks."
  (rf/reg-sub ::tasks
    :<- [::db]
    :-> (comp vals :tasks)))

(defn running?
  "Checks for running task in app-db also filtered via `name`. Also see subscription `::running?`."
  ([db]
   (some? (get-tasks db)))

  ([db name]
   (some? (get-task-by-name db name))))

(def ^{:rf/reg-sub ::running?} running?-sub
  "re-frame subscription for running tasks.´ optional filtered by given `name`."
  (rf/reg-sub ::running?
    :<- [::tasks]
    (fn [tasks [_ name]]
      (cond->> tasks
        name
        (some #(= (:name %) name))

        :always
        (some?)))))

(defn attach-after-event
  "Attaches event to call after task completion."
  [db id-or-task event]
  (update-in db [::db :tasks (->id id-or-task) ::after-events] (fnil conj []) event))

(defn- attach-effect
  [db id-or-task effect]
  (update-in db [::db :tasks (->id id-or-task) ::effects] (fnil conj #{}) effect))

(defn- unattach-effect
  [db id-or-task effect]
  (update-in db [::db :tasks (->id id-or-task) ::effects] (fnil disj #{}) effect))

(defn register
  "Registers task within app-db. Also see event `::register`.
   Tasks can be used via subscriptions `::tasks` and `::running?`."
  [db {:keys [::id] :as task}]
  (log/trace "registering task" task)
  (assoc-in db [::db :tasks id] task))

(def ^{:rf/reg-event ::register} register-event
  "re-frame event to register `task`. See `register`."
  (rf/reg-event-db ::register
    (fn [db [_ task]]
      (register db task))))

(defn unregister
  "Unregisters task within app-db. Also see event `::unregister`.
   Tasks can be used via subscriptions `::tasks` and `::running?`."
  [db id-or-task]
  (log/trace "unregistering task" id-or-task)
  (update-in db [::db :tasks] dissoc (->id id-or-task)))

(def ^{:rf/reg-event ::unregister} unregister-event
  "re-frame event to unregister `task`. See `unregister`."
  (rf/reg-event-fx ::unregister
    (fn [{:keys [db]} [_ id-or-task]]
      (let [{:keys [::id ::after-events]}
            (get-task db id-or-task)]

        {:db
         (unregister db id)

         :dispatch-n
         (vec after-events)}))))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Original Events

(defn task-event?
  "Checks if event is task based."
  [event]
  (let [[event-name & _]
        event]

    (= event-name ::unregister-and-dispatch-original)))

(defn get-original-event
  "Gets original event of task event or `event` itself."
  [event]
  (let [[_event-name _task _effect maybe-original-event]
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
    (assoc event 3 original-event)
    event))

(defn update-original-event
  "Updates original event of maybe task `event`, returns maybe modified `event`."
  [event f & args]
  (if (task-event? event)
    (apply update event 3 f args)
    event))

(defn ensure-original-event
  "Ensures an `original-event` for direct use or with task."
  [event original-event]
  (if (some-original-event? event)
    event
    (if (task-event? event)
      (assoc-orignal-event event original-event)
      original-event)))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Wait For

(defn- delay-event
  [context tasks event]
  (log/trace "delaying event" {:context context, :tasks tasks, :event event})
  (update-app-db context attach-after-event (first tasks) event))

(defn wait-for
  "Creates an interceptor to wait for task aka. queue event execution during running `tasks`.
   `tasks` can be
   - `:any` for any task
   - a task name
   - a collection of task names
   - a 1-argument function given all running tasks to filter for blocking tasks

   Can be injected multiple times, consider injection order.
   Can be used as global interceptor, consider there is no direct reversal allow / pass
   functionality. To not wait for tasks but call event add event vector meta `::wait-for {:ignore-tasks #{task-name ,,,}}.`

   Sugar `debounce-ms` to also inject `debounce` interceptor."
  ([]
   (wait-for :any))

  ([tasks]
   (wait-for tasks nil))

  ([tasks debounce-ms]
   (cond-> []
     :always
     (conj (rf/->interceptor
            :id ::wait-for

            :before
            (let [filter-blocking-tasks
                  (cond
                    (fn? tasks)
                    tasks

                    (= tasks :any)
                    identity

                    (coll? tasks)
                    #(filter (comp (partial contains? (set tasks)) :name) %)

                    :else
                    #(filter (comp (partial = tasks) :name) %))]

              (fn [context]
                (log/trace "waiting for tasks"
                  {:context context
                   :tasks tasks
                   :debounce-ms debounce-ms})
                (let [[original-event-name :as original-event]
                      (-get-original-event context)

                      events-to-pass
                      #{::unregister
                        ::unregister-and-dispatch-original}

                      tasks-to-ignore
                      (-> original-event
                          (meta)
                          (::wait-for)
                          (:ignore-tasks)
                          (set))

                      blocking-tasks
                      (->> context
                           (get-app-db)
                           (get-tasks)
                           (filter-blocking-tasks)
                           (remove (comp tasks-to-ignore :name)))]

                  (cond
                    (contains? events-to-pass original-event-name)
                    context

                    (not (empty? blocking-tasks))
                    (-> context
                        (abort-original-event)
                        (delay-event blocking-tasks original-event))

                    :else context))))))

     (some? debounce-ms)
     (conj (debounce debounce-ms)))))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; as-task

(defn- task-name-by-original-event
  [context]
  (let [[event-name :as _event]
        (-get-original-event context)]

    event-name))

(defn- normalize-task
  [name-or-task]
  (if (map? name-or-task)
    name-or-task
    {:name name-or-task}))

(def ^{:private true, :rf/reg-event ::unregister-and-dispatch-original} unregister-and-dispatch-original-event
  (rf/reg-event-fx ::unregister-and-dispatch-original
    (fn [{:keys [db]} [_ task effect original-event]]
      (let [task-completed?
            (-> db
                (get-task task)
                (::effects)
                (disj effect)
                (empty?))]

        {:db
         (unattach-effect db task effect)

         :fx
         (cond-> []
           (some? original-event)
           (conj [:dispatch original-event])

           task-completed?
           (conj [:dispatch [::unregister task]]))}))))

(def ^:private !completion-keys-per-effect
  (atom {}))

(defn- get-completion-keys-for-effect
  [effect]
  (if-let [completion-keys (get @!completion-keys-per-effect effect)]
    completion-keys
    (log/warn "no completion keys set for effect" {:effect effect})))

(defn reg-completion-keys-for-effect
  "Registers effect completion keys to use with `as-task`."
  [effect-key & completion-keys]
  (swap! !completion-keys-per-effect assoc effect-key (set completion-keys)))

(defn- unregister-by-effect-completion-key
  [effect effect-key completion-key task]
  (update effect completion-key (partial vector ::unregister-and-dispatch-original task effect-key)))

(defn- unregister-by-effect-completion-keys
  [effect effect-key completion-keys task]
  (reduce
   (fn [effect completion-key]
     (unregister-by-effect-completion-key
      effect effect-key completion-key task))
   effect
   completion-keys))

(defn- unregister-by-effect
  [context task effect-key]
  (let [completion-keys
        (get-completion-keys-for-effect effect-key)]

    (cond-> context
      (and
       (contains-effect? context effect-key)
       (some? completion-keys))
      (-> (update-effect effect-key unregister-by-effect-completion-keys effect-key completion-keys task)
          (update-app-db attach-effect task effect-key)))))

(defn- unregister-by-effects
  [context task effects]
  (reduce
   (fn [context effect]
     (unregister-by-effect context task effect))
   context
   effects))

(def ^{:private true, :rf/reg-cofx ::uuid} uuid-cofx
  (rf/reg-cofx ::uuid
    (fn [coeffects]
      (assoc coeffects ::uuid (random-uuid)))))

(defn as-task
  "Creates an interceptor to mark an event and its effects as task. Also see `wait-for` to wait for task.
   Give it a name of the task or map with at least a `:name` key or nil / nothing to use the event name.
   Tasks can be used via subscriptions `::tasks` and `::running?`.

   Given vector `effects` will be used to identify effects to monitor for the task. Can be the keyword of the effect or an vector of effects path (to handle special :fx effect). Completion keys must be registered by `reg-completion-keys-for-effect` for the effects.

   Given sugar `wait-for-tasks` to also inject `wait-for` interceptor, see documentation `wait-for`.
   Given sugar `debounce-ms` to also inject `debounce` interceptor, see documentation `debounce`.

   Within your event handler use `::task` as effect to modify your task data.

   See `*-original-event` functions to handle as-tasks effect completion handlers.
   See `attach-after-event` to attach events to call after task completion."
  ([]
   (as-task nil))

  ([name-or-task]
   (as-task name-or-task nil))

  ([name-or-task effects]
   (as-task name-or-task effects nil))

  ([name-or-task effects wait-for-tasks]
   (as-task name-or-task effects wait-for-tasks nil))

  ([name-or-task effects wait-for-tasks debounce-ms]
   ;; WATCHOUT: `into`, not `conj`. Both branches add a collection of
   ;; interceptors, and conj-ing one nests it. re-frame flattens an event's
   ;; interceptor chain, so nesting went unnoticed there -- but
   ;; `reg-global-interceptor` takes one interceptor at a time and reads
   ;; `:id` off what it gets, so it silently registered nothing.
   (cond-> []
     (some? wait-for-tasks)
     (into (wait-for wait-for-tasks))

     (some? debounce-ms)
     (conj (debounce debounce-ms))

     :always
     (into [(rf/inject-cofx ::uuid)
            (rf/->interceptor
             :id ::as-task

             :after
             (fn [context]
               (log/trace "creating event as task"
                 {:context context
                  :name-or-task name-or-task
                  :effects effects
                  :wait-for-tasks wait-for-tasks
                  :debounce-ms debounce-ms})
               (let [task
                     (-> name-or-task
                         (or (task-name-by-original-event context))
                         (normalize-task)
                         (merge (interceptor/get-effect context ::task))
                         (assoc ::event (-get-original-event context))
                         (assoc ::id (interceptor/get-coeffect context ::uuid)))

                     context-with-task
                     (-> context
                         ;; NOTE: ::task effect is only to carry task data
                         (update :effects dissoc ::task)
                         ;; NOTE: ::uuid coeffect is only to generate an task-id
                         (update :coeffects dissoc ::uuid)
                         (update-app-db register task)
                         (unregister-by-effects task effects))

                     no-unregister-effects?
                     (-> context-with-task
                         (get-app-db)
                         (get-task task)
                         (::effects)
                         (empty?))]

                 (cond-> context-with-task
                   ;; NOTE: no effects to unregister task, then unregister immediately
                   no-unregister-effects?
                   (update-app-db unregister task)))))]))))
