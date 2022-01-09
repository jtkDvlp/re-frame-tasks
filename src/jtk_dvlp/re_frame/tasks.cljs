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
  "Unregister task within app state. Also see event `::unregister`.
   Tasks can be used via subscriptions `::tasks` and `::running?`."
  [db {:keys [::id]}]
  (update-in db [::db :tasks] dissoc id))

(def ^:private !global-default-completion-keys
  (atom #{:on-complete :on-success :on-failure :on-error}))

(def set-global-default-completion-keys!
  "Sets global completion keys."
  (partial reset! !global-default-completion-keys))

(def merge-global-default-completion-keys!
  "Merge global completion keys."
  (partial swap! !global-default-completion-keys merge))


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
    {::name name-or-task}))

(defn- normalize-effect-key
  [effect-key]
  (let [[effect :as effect-key]
        (cond-> effect-key
          (not (vector? effect-key))
          (vector))]

    (cond-> effect-key
      (= effect :fx)
      (conj 1))))

(defn- normalize-fx
  [fx]
  (cond
    (keyword? fx)
    {:effect-key (normalize-effect-key fx)
     :completion-keys @!global-default-completion-keys}

    (vector? fx)
    {:effect-key (normalize-effect-key (first fx))
     :completion-keys (some-> (next fx) (into #{}))}

    :else
    {:effect-key (normalize-effect-key (:effect-key fx))
     :completion-keys (:completion-keys fx)}))

(defonce ^:private !task<->fxs-counters
  (atom {}))

(defn- unregister-by-fx
  [effect completion-keys task]
  (reduce
   (fn [effect completion-key]
     (update effect completion-key (partial vector ::unregister-and-dispatch-original task)))
   effect
   completion-keys))

(defn- unregister-for-fx
  [effects completion-keys task]
  (mapv
   (fn [[effect-key effect-value]]
     [effect-key (unregister-by-fx effect-value completion-keys task)])
   effects))

(defn- unregister-by-fxs
  [{:keys [effects] :as context}
   {:keys [::id] :as task}
   fxs]

  (loop [n 0

         [{:keys [effect-key completion-keys]} & rest-fxs]
         fxs

         {:key [effects] :as context}
         context]

    (let [effect (get-in effects effect-key)]
      (if effect-key
        (->> task
             (unregister-by-fx effect completion-keys)
             (assoc-in context (cons :effects effect-key))
             (recur (inc n) rest-fxs))

        (when (> n 0)
          (swap! !task<->fxs-counters assoc id n)
          context)))))

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

        task
        (assoc task ::id dispatch-id)]

    (if (fx-handler-run? context)
      (or
       (unregister-by-fxs context task fxs)
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
   Give it a name of the task or map with at least a `::name` key or nil / nothing to use the event name.
   Tasks can be used via subscriptions `::tasks` and `::running?`.

   Given vector `fxs` will be used to identify effects to monitor for the task. Can be the keyword of the effect or an vector of effect keyword or effect path (to handle special :fx effect) and completion keywords to hang in. Completion keys defaults to `:on-complete`, `:on-success`, `on-failure` and `on-error`. See also `set-global-default-completion-keys!` and `merge-global-default-completion-keys!`.

   Works in combination with https://github.com/jtkDvlp/re-frame-async-coeffects. For async coeffects there is no need to define what to monitor. Coeffects will be monitored automatically."
  ([]
   (as-task nil))

  ([name-or-task]
   (as-task name-or-task nil))

  ([name-or-task fxs]
   (let [fxs (map normalize-fx fxs)]
     (rf/->interceptor
      :id
      :as-task

      :after
      (fn [context]
        (let [task
              (-> name-or-task
                  (or (task-by-original-event context))
                  (normalize-task)
                  (assoc ::event (get-original-event context)))]

          (cond
            (includes-acofxs? context)
            (handle-acofx-variant context task fxs)

            :else
            (handle-straight-variant context task fxs))))))))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Events

(rf/reg-event-db ::register
  (fn [db [_ task]]
    (register db task)))

(rf/reg-event-db ::unregister
  (fn [db [_ task]]
    (unregister db task)))

(rf/reg-event-fx ::unregister-and-dispatch-original
  (fn [_ [_ task original-event-vec & original-event-args]]
    {::unregister-and-dispatch-original [task original-event-vec original-event-args]}))

(rf/reg-fx ::unregister-and-dispatch-original
  (fn [[{:keys [::id] :as task} original-event-vec original-event-args]]
    (when original-event-vec
      (rf/dispatch (into original-event-vec original-event-args)))

    (if (= 1 (get @!task<->fxs-counters id))
      (do
        (swap! !task<->fxs-counters dissoc id)
        (rf/dispatch [::unregister task]))
      (swap! !task<->fxs-counters update id dec))))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Subscriptions

(rf/reg-sub ::db
  (fn [{:keys [::db]}]
    db))

(rf/reg-sub ::tasks
  :<- [::db]
  (fn [{:keys [tasks]}]
    tasks))

(rf/reg-sub ::running?
  :<- [::tasks]
  (fn [tasks [_ name]]
    (if name
      (->> tasks (vals) (filter #(= (::name %) name)) (first) (some?))
      (-> tasks (first) (some?)))))
