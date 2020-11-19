(ns jtk-dvlp.your-project
  (:require
   [re-frame.core :as rf]
   [jtk-dvlp.re-frame.tasks :as tasks]))


(rf/reg-event-fx :some-event
  ;; give it the fx to identify the task emitted by this event
  [(tasks/as-task :some-fx)
   ;; of course you can use this interceptor for more than one fx in a event call
   ;; futher more you can give it the handler keys to hang in finishing the task
   (tasks/as-task :some-other-fx :on-done :on-done-with-errors)]
  (fn [_ _]
    {:some-fx
     {,,,
      ;; you can give the tasks an id (default: uuid), see subscription `:jtk-dvlp.re-frame.tasks/running?` for usage.
      ::tasks/id :some-important-stuff
      :label "Do some fx"
      :on-success [:some-event-success]
      :on-error [:some-event-error]
      ;; calling this by `:some-fx` will unregister the task via `tasks/as-task`
      :on-completed [:some-event-completed]}

     :some-other-fx
     {,,,
      :label "Do some other fx"
      ;; calling this by some-fx will unregister the task via `tasks/as-task`
      ;; `:on-done-with-error` will also untergister the task when called by `:some-other-fx`
      :on-done [:some-other-event-completed]}}))

(defn app-view
  []
  (let [block-ui?
        ;; for sugar you can give it also a pred (called with the task id) e.g. a set of task ids to filter the running tasks.
        (rf/subscribe [:jtk-dvlp.re-frame.tasks/running?])

        any-running-task?
        (rf/subscribe [:jtk-dvlp.re-frame.tasks/running?])

        some-important-stuff-running?
        (rf/subscribe [:jtk-dvlp.re-frame.tasks/running? #{:some-important-stuff}])

        tasks
        (rf/subscribe [:jtk-dvlp.re-frame.tasks/tasks])]

    (fn []
      [:<>
       [:div "some app content"]

       [:ul "task list"
        ;; each task is the original fx map plus an `::tasks/id` and `::tasks/effect`
        (for [{:keys [label :jtk-dvlp.re-frame.tasks/id] :as _task} @tasks]
          ^{:key id}
          [:li label])]

       (when @block-ui?
         [:div "this div blocks the UI if there are running tasks"])])))
