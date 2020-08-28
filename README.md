[![Clojars Project](https://img.shields.io/clojars/v/jtk-dvlp/re-frame-tasks.svg)](https://clojars.org/jtk-dvlp/re-frame-tasks)
[![cljdoc badge](https://cljdoc.org/badge/jtk-dvlp/re-frame-tasks)](https://cljdoc.org/d/jtk-dvlp/re-frame-tasks/CURRENT)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://github.com/jtkDvlp/re-frame-tasks/blob/master/LICENSE)

# Tasks interceptor / helpers for re-frame

Interceptor and helpers to register and unregister (background-)tasks (FXs) in your app-state / app-db to list tasks and / or block single ui parts or the whole ui.

## Features

* register / unregister tasks / fxs via one line interceptor injection
  * support multiple and any fx on-completion keys
* subscriptions for tasks list and running task boolean
* events to register / unregister tasks yourself
* helpers to register / unregister tasks into db yourself

## Getting started

### Get it / add dependency

Add the following dependency to your `project.clj`:<br>
[![Clojars Project](https://img.shields.io/clojars/v/jtk-dvlp/re-frame-tasks.svg)](https://clojars.org/jtk-dvlp/re-frame-tasks)

### Usage

```clojure
(ns jtk-dvlp.your-project
  (:require
   [re-frame.core :as rf]
   [jtk-dvlp.re-frame.tasks :as tasks]))


(rf/reg-event-fx :some-event
  ;; give it the fx to identity the task
  [(tasks/as-task :some-fx)
   ;; of course you can use this interceptor more for than one fx in a event call
   ;; futher more you can give it the handler keys to hang in finishing the task
   (tasks/as-task :some-other-fx :on-done :on-done-with-errors)]
  (fn [_ _]
    {:some-fx
     {,,,
      :label "Do some fx"
      :on-success [:some-event-success]
      :on-error [:some-event-error]
      ;; Calling this by `:some-fx` will unregister the task
      :on-completed [:some-event-completed]}

     :some-other-fx
     {,,,
      :label "Do some other fx"
      ;; Calling this by some-fx will unregister the task
      ;; `:on-done-with-error` will also untergister the task when called by `:some-other-fx`
      :on-done [:some-other-event-completed]}}))

(defn app-view
  []
  (let [block-ui?
        (rf/subscribe [:jtk-dvlp.re-frame.tasks/running?])

        tasks
        (rf/subscribe [:jtk-dvlp.re-frame.tasks/tasks])]

    (fn []
      [:<>
       [:div "some app content"]

       [:ul "task list"
        ;; each task is the original fx map plus an `::tasks/id`
        (for [{:keys [label :jtk-dvlp.re-frame.tasks/id] :as _task} @tasks]
          ^{:key id}
          [:li label])]

       (when @block-ui?
         [:div "this div blocks the UI if there are running tasks"])])))

```

## Appendix

I´d be thankful to receive patches, comments and constructive criticism.

Hope the package is useful :-)
