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
;; Helpers: Coeffects

(defn- compose-coeffects
  [inner-cofx outer-cofx]
  (let [{outer-before-fn :before
         outer-after-fn :after}
        outer-cofx

        {inner-before-fn :before
         inner-after-fn :after}
        inner-cofx]

    ;; WATCHOUT: Every test of a `cond->` that holds is applied. A clause
    ;; for "inner only" therefore has to exclude the case the clause above
    ;; it already handled -- otherwise the composition is built and then
    ;; overwritten by the inner function alone, and the outer one is gone
    ;; without a word.
    (cond-> outer-cofx
      (and (some? inner-before-fn) (some? outer-before-fn))
      (update :before #(comp %2 %1) inner-before-fn)

      (and (some? inner-before-fn) (nil? outer-before-fn))
      (assoc :before inner-before-fn)

      (and (some? inner-after-fn) (some? outer-after-fn))
      (update :after #(comp %2 %1) inner-after-fn)

      (and (some? inner-after-fn) (nil? outer-after-fn))
      (assoc :after inner-after-fn))))


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

(defn- claim-in-db
  [db id-or-task claim-key]
  (update-in db [::db :tasks (->id id-or-task) ::claims]
             (fnil conj #{}) claim-key))

(defn- release-in-db
  [db id-or-task claim-key]
  (update-in db [::db :tasks (->id id-or-task) ::claims]
             (fnil disj #{}) claim-key))

(defn- claims
  [db id-or-task]
  (-> db (get-task id-or-task) (::claims)))

(defn- unclaimed?
  [db id-or-task]
  (-> db (claims id-or-task) (empty?)))

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


(defn task-id
  "Id of the task this event run belongs to, or nil when the run carries
   none. Hand it to whatever will continue the run later, see `resume`."
  [context]
  (::id context))

(defn claim
  "Keeps the task of this event run registered although the run is about to
   end without effects to wait for -- because the caller takes over and
   will continue it later.

   `claim-key` identifies the claim and has to be unique among the claims
   of one task; an id the caller already holds per suspension does nicely.
   Give the same key back to `release`.

   Whoever claims, releases -- in every outcome. A claim that is never
   given back leaves the task registered for good, and anything waiting
   for it waits for good. Where there is no run left to release in, an
   error path typically, there is the event `::release`."
  [context claim-key]
  (if (some? (task-id context))
    (do
      (log/trace "claiming task"
        {:task-id (task-id context), :claim claim-key})
      ;; WATCHOUT: Noted in the context, not written to app-db. re-frame
      ;; builds the `:db` effect by handing the event handler the db from
      ;; the *coeffects*, so anything an earlier `:before` wrote there is
      ;; overwritten the moment the handler runs. `as-task` applies what is
      ;; noted here in its `:after`, which is past that point.
      (update context ::claimed (fnil conj #{}) claim-key))
    (do
      ;; There is no task to claim when `as-task` did not run before this
      ;; interceptor. Silently building one under a nil id would hide the
      ;; wrong order until someone wonders why nothing is ever waited for.
      (log/warn "no task to claim -- is `as-task` missing or ordered after"
        {:claim claim-key})
      context)))

(defn release
  "Gives back a claim taken with `claim`, from within an event run. Use the
   event `::release` where there is no run to hand it back in."
  [context claim-key]
  (if (some? (task-id context))
    (do
      (log/trace "releasing claim"
        {:task-id (task-id context), :claim claim-key})
      (update context ::released (fnil conj #{}) claim-key))
    (do
      (log/warn "no task to release a claim of" {:claim claim-key})
      context)))

(defn- apply-noted-claims
  [context task]
  (let [{:keys [::claimed ::released]}
        context]

    (update task ::claims
            (fn [claims]
              (reduce disj (into (or claims #{}) claimed) released)))))

(defn resume
  "Marks `event` as the continuation of the task `task-id`, so dispatching
   it picks that task up again instead of opening a second one. `wait-for`
   lets such an event past the very task it continues."
  [event task-id]
  (vary-meta event assoc ::resume task-id))

(defn- resumed-task-id
  [event]
  (-> event (meta) (::resume)))

(def ^{:rf/reg-event ::release} release-event
  "re-frame event to give back a claim outside of an event run, see
   `claim`. Completes the task when it was the last one."
  (rf/reg-event-fx ::release
    (fn [{:keys [db]} [_ id-or-task claim-key]]
      (let [db
            (release-in-db db id-or-task claim-key)]

        (cond-> {:db db}
          (unclaimed? db id-or-task)
          (assoc :dispatch [::unregister id-or-task]))))))


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
   (let [wait-for
         (rf/->interceptor
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

                  ;; An event that continues one of the blocking tasks is
                  ;; that task, not a competitor for it.
                  (->> blocking-tasks
                       (some #(= (::id %) (resumed-task-id original-event)))
                       (and (resumed-task-id original-event)))
                  context

                  (not (empty? blocking-tasks))
                  (-> context
                      (abort-original-event)
                      (delay-event blocking-tasks original-event))

                  :else context)))))]

     (cond->> wait-for
       (some? debounce-ms)
       (compose-coeffects (debounce debounce-ms))))))


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
      (let [db
            (release-in-db db task effect)

            task-completed?
            (unclaimed? db task)]

        {:db
         db

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
          (update-app-db claim-in-db task effect-key)))))

(defn- unregister-by-effects
  [context task effects]
  (reduce
   (fn [context effect]
     (unregister-by-effect context task effect))
   context
   effects))

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
   (let [as-task
         (rf/->interceptor
          :id ::as-task

          ;; The id has to exist before anything can claim the task: a
          ;; claiming interceptor runs its `:before` while this one is
          ;; still only holding an id, and `:after` is where the task is
          ;; actually written.
          :before
          (fn [context]
            (let [event
                  (-get-original-event context)]

              (assoc context ::id
                     (or (resumed-task-id event) (random-uuid)))))

          :after
          (fn [context]
            (log/trace "creating event as task"
              {:context context
               :name-or-task name-or-task
               :effects effects
               :wait-for-tasks wait-for-tasks
               :debounce-ms debounce-ms})
            (let [id
                  (task-id context)

                  task
                  (->> (-> name-or-task
                           (or (task-name-by-original-event context))
                           (normalize-task)
                           (merge (interceptor/get-effect context ::task))
                           (assoc ::event (-get-original-event context))
                           (assoc ::id id))
                       ;; NOTE: What is already under this id comes first:
                       ;; the state of the run this one continues.
                       (merge (-> context (get-app-db) (get-task id)))
                       (apply-noted-claims context))

                  context-with-task
                  (-> context
                      ;; NOTE: ::task effect is only to carry task data
                      (update :effects dissoc ::task)
                      ;; NOTE: this interceptor's own scratch space
                      (dissoc ::id ::claimed ::released)
                      (update-app-db register task)
                      (unregister-by-effects task effects))

                  completed?
                  (-> context-with-task
                      (get-app-db)
                      (unclaimed? task))]

              (cond-> context-with-task
                ;; NOTE: nothing left to wait for, so the task is done
                completed?
                (update-app-db unregister task)))))]

     (cond->> as-task

       (some? wait-for-tasks)
       (compose-coeffects (wait-for wait-for-tasks))

       (some? debounce-ms)
       (compose-coeffects (debounce debounce-ms))))))
