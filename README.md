[![Clojars Project](https://img.shields.io/clojars/v/jtk-dvlp/re-frame-tasks.svg)](https://clojars.org/jtk-dvlp/re-frame-tasks)
[![cljdoc badge](https://cljdoc.org/badge/jtk-dvlp/re-frame-tasks)](https://cljdoc.org/d/jtk-dvlp/re-frame-tasks/CURRENT)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://github.com/jtkDvlp/re-frame-tasks/blob/master/LICENSE)

# Tasks interceptor / helpers for re-frame

Interceptor and helpers to register and unregister (background-)tasks (FXs) in your app-state / app-db to list tasks and / or block single ui parts or the whole ui.

## Features

* register / unregister tasks / fxs via one line interceptor injection
  * support multiple and any fx on-completion keys
* subscriptions for tasks list and running task boolean
  * running task boolean can be quick filtered by task name
* events to register / unregister tasks yourself
* helpers to register / unregister tasks into db yourself

Also works for async coeffects injections, see https://github.com/jtkDvlp/re-frame-async-coeffects.

## Getting started

### Get it / add dependency

Add the following dependency to your `project.clj`:<br>
[![Clojars Project](https://img.shields.io/clojars/v/jtk-dvlp/re-frame-tasks.svg)](https://clojars.org/jtk-dvlp/re-frame-tasks)

### Usage

See api docs [![cljdoc badge](https://cljdoc.org/badge/jtk-dvlp/re-frame-tasks)](https://cljdoc.org/d/jtk-dvlp/re-frame-tasks/CURRENT)

```clojure
(ns jtk-dvlp.your-project
  (:require
   [re-frame.core :as rf]
   [jtk-dvlp.re-frame.tasks :as tasks]))


(rf/reg-event-fx :some-event
  ;; give it the fx to identify the task emitted by this event
  [(tasks/as-task :some-task
     [:some-fx
      [:some-other-fx :on-done :on-done-with-errors]
      [[:fx 1] :on-done]])
   (acoeffects/inject-acofx :some-acofx)]
  (fn [_ _]
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

       [:ul "task list " (count @tasks)
        ;; each task is the original fx map plus an `::tasks/id`, the original `event`
        ;; and the data you carry via `::task` fx from within the event
        (for [{:keys [::tasks/id] :as task} @tasks]
          ^{:key id}
          [:li [:pre (with-out-str (cljs.pprint/pprint task))]])]

       (when @block-ui?
         [:div "this div blocks the UI if there are running tasks"])])))

```

## Appendix

I´d be thankful to receive patches, comments and constructive criticism.

Hope the package is useful :-)
