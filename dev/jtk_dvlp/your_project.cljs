(ns ^:figwheel-hooks jtk-dvlp.your-project
  (:require
   [cljs.pprint]
   [cljs.core.async :refer [timeout]]
   [jtk-dvlp.async :refer [go <!] :as a]

   [goog.dom :as gdom]
   [reagent.dom :as rdom]

   [re-frame.core :as rf]
   [jtk-dvlp.re-frame.async-coeffects :as acoeffects]
   [jtk-dvlp.re-frame.tasks :as tasks]))


(defn- some-async-stuff
  []
  (go
    (<! (timeout 5000))
    :result))

(rf/reg-fx :some-fx
  (fn [{:keys [on-complete] :as x}]
    (println ":some-fx" x)
    (go
      (let [result (<! (some-async-stuff))]
        (println ":some-fx finished")
        (rf/dispatch (conj on-complete result))))))

(rf/reg-fx :some-other-fx
  (fn [{:keys [bad-data on-done on-done-with-errors] :as x}]
    (println ":some-other-fx" x)
    (go
      (try
        (when bad-data
          (throw (ex-info "bad data" {:code :bad-data})))
        (let [result (<! (some-async-stuff))]
          (rf/dispatch (conj on-done result)))
        (catch :default e
          (println ":some-other-fx error" e)
          (rf/dispatch (conj on-done-with-errors e)))
        (finally
          (println ":some-other-fx finished"))))))

(acoeffects/reg-acofx :some-acofx
  (fn [cofxs & [bad-data :as args]]
    (println ":some-acofx")
    (go
      (when bad-data
        (throw (ex-info "bad data" {:code :bad-data})))
      (let [result
            (->> args
                 (apply some-async-stuff)
                 (<!)
                 (assoc cofxs :some-acofx))]

        (println ":some-acofx finished")
        result))))

(rf/reg-event-fx :some-event
  ;; give it the fx to identify the task emitted by this event
  [(tasks/as-task :some-task
     [:some-fx
      [:some-other-fx :on-done :on-done-with-errors]
      [[:fx 1] :on-done]])
   (acoeffects/inject-acofx :some-acofx)]
  (fn [_ _]
    (println "handler")
    {;; modify your task via fx
     ::tasks/task
     {:this-is-some-task :data}

     :some-fx
     {,,,
      ;; you can give the tasks an id (default: uuid), see subscription `:jtk-dvlp.re-frame.tasks/running?` for usage.
      :on-success [:some-event-success]
      :on-error [:some-event-error]
      ;; calling this by `:some-fx` will unregister the task via `tasks/as-task`
      :on-complete [:some-event-completed]}

     :some-other-fx
     {,,,
      ;; calling this by some-fx will unregister the task via `tasks/as-task`
      ;; `:on-done-with-error` will also untergister the task when called by `:some-other-fx`
      :on-done [:some-other-event-completed]}

     :fx
     [[:some-fx
       {:on-success [:some-event-success]
        :on-error [:some-event-error]
        :on-complete [:some-event-completed]}]

      ;; same with :fx effect referenzed via path [:fx 1]
      [:some-other-fx
       {:on-done [:some-other-event-completed]}]]}))

(rf/reg-event-fx :some-bad-event
  ;; give it the fx to identify the task emitted by this event
  [(tasks/as-task :some-task
     [:some-fx
      {:effect-key [:some-other-fx]
       :completion-keys #{:on-done :on-done-with-errors}}])
   (acoeffects/inject-acofx [:some-acofx :bad-data])]
  (fn [_ _]
    (println "handler")
    {:some-fx
     {,,,
      ;; you can give the tasks an id (default: uuid), see subscription `:jtk-dvlp.re-frame.tasks/running?` for usage.
      :on-success [:some-event-success]
      :on-error [:some-event-error]
      ;; calling this by `:some-fx` will unregister the task via `tasks/as-task`
      :on-complete [:some-event-completed]}

     :some-other-fx
     {,,,
      ;; calling this by some-fx will unregister the task via `tasks/as-task`
      ;; `:on-done-with-error` will also untergister the task when called by `:some-other-fx`
      :on-done [:some-other-event-completed]}}))

(rf/reg-event-fx :some-other-bad-event
  ;; give it the fx to identify the task emitted by this event
  [(tasks/as-task :some-task
     [:some-fx
      {:effect-key [:some-other-fx]
       :completion-keys #{:on-done :on-done-with-errors}}])
   (acoeffects/inject-acofx :some-acofx)]
  (fn [_ _]
    (println "handler")
    {:some-fx
     {,,,
      ;; you can give the tasks an id (default: uuid), see subscription `:jtk-dvlp.re-frame.tasks/running?` for usage.
      :on-success [:some-event-success]
      :on-error [:some-event-error]
      ;; calling this by `:some-fx` will unregister the task via `tasks/as-task`
      :on-complete [:some-event-completed]}

     :some-other-fx
     {,,,
      :bad-data true
      ;; calling this by some-fx will unregister the task via `tasks/as-task`
      ;; `:on-done-with-error` will also untergister the task when called by `:some-other-fx`
      :on-done [:some-other-event-completed]}}))

(rf/reg-event-db :some-event-completed
  (fn [db _]
    db))

(rf/reg-event-db :some-other-event-completed
  (fn [db _]
    db))

(defn app-view
  []
  (let [block-ui?
        (rf/subscribe [:jtk-dvlp.re-frame.tasks/running?])

        tasks
        (rf/subscribe [:jtk-dvlp.re-frame.tasks/tasks])]

    (fn []
      [:<>
       [:button {:on-click #(rf/dispatch [:some-event])}
        "exec some event"]
       [:button {:on-click #(rf/dispatch [:some-bad-event])}
        "exec some bad event"]
       [:button {:on-click #(rf/dispatch [:some-other-bad-event])}
        "exec some other bad event"]

       [:ul "task list " (count @tasks)
        ;; each task is the original fx map plus an `::tasks/id`, the original `event`
        ;; and the data you carry via `::task` fx from within the event
        (for [{:keys [::tasks/id] :as task} @tasks]
          ^{:key id}
          [:li [:pre (with-out-str (cljs.pprint/pprint task))]])]

       (when @block-ui?
         [:div "this div blocks the UI if there are running tasks"])])))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; re-frame setup

(defn- mount-app
  []
  (rdom/render
    [app-view]
    (gdom/getElement "app")))

(defn ^:after-load on-reload
  []
  (rf/clear-subscription-cache!)
  (mount-app))

(defonce on-init
  (mount-app))
