(ns jtk-dvlp.re-frame.tasks
  (:require
   [re-frame.core :as rf]
   [re-frame.interceptor :as interceptor]))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Functions

(defn ->task
  [data]
  (assoc data ::id (random-uuid)))

(defn register
  [db {:keys [::id] :as task}]
  (assoc-in db [::db :tasks id] task))

(defn unregister
  [db id-or-task]
  (let [id (or (::id id-or-task) id-or-task)]
    (update-in db [::db :tasks] dissoc id)))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Interceptors

(defn as-task
  ([effect-key]
   (as-task effect-key :on-completed))

  ([effect-key & on-completion-keys]
   (rf/->interceptor
    :id
    :as-task

    :after
    (fn [context]
      (let [task-id
            (random-uuid)

            effect
            (rf/get-effect context effect-key)

            task
            (assoc effect ::id task-id)

            effect'
            (->> on-completion-keys
                 (map #(->> (get effect %)
                            (vector ::unregister-and-dispatch-original task-id)))
                 (zipmap on-completion-keys)
                 (merge effect))

            db'
            (-> (rf/get-effect context :db)
                (or (rf/get-coeffect context :db))
                (register task))]

        (if effect
          (-> context
              (interceptor/assoc-effect :db db')
              (interceptor/assoc-effect effect-key effect'))
          context))))))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Events

(rf/reg-event-db ::register
  (fn [db [_ task]]
    (register db task)))

(rf/reg-event-db ::unregister
  (fn [db [_ id-or-task]]
    (unregister db id-or-task)))

(rf/reg-event-fx ::unregister-and-dispatch-original
  (fn [{:keys [db]} [_ id-or-task original-event-vec & original-event-args]]
    {:db (unregister db id-or-task)
     :dispatch (into original-event-vec original-event-args)}))


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
  (fn [tasks [_ pred]]
    (if pred
      (->> tasks (filter pred) (some?))
      (->> tasks (seq) (some?)))))
