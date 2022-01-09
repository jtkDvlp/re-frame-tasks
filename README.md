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

Alsoe works for async coeffects injections, see https://github.com/jtkDvlp/re-frame-async-coeffects.

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
  ;; give it a name and fx keys to identify the task and effects emitted by this event.
  [(tasks/as-task :some-task [:some-fx :some-other-fx])
   ;; futher more you can give it the handler keys to hang in finishing the tasks effects
   ;; (tasks/as-task :some-task
   ;;                [[:some-fx :on-done :on-done-with-errors]
   ;;                 [:some-other-fx :on-yuhu :on-oh-no]])
   ;; last but not least supports the special effect :fx giving an path for the fx to monitor.
   ;; (tasks/as-task :some-task [[[:fx 1] :on-done ,,,] ; you need to give it the index of the effect within :fx vector to monitor.
   ;;                            ,,,])
   ]
  (fn [_ _]
    {:some-fx
     {:label "Do some fx"
      :on-success [:some-event-success]
      :on-error [:some-event-error]
      :on-done [:some-event-completed]}

     :some-other-fx
     {,,,
      :label "Do some other fx"
      ;; `:on-done-with-error` will also untergister the task when called by `:some-other-fx`
      :on-done [:some-other-event-completed]}}))

(defn app-view
  []
  (let [block-ui?
        (rf/subscribe [:jtk-dvlp.re-frame.tasks/running?])

        ;; for sugar you can give it also a task name to filter the running tasks.
        some-important-stuff-running?
        (rf/subscribe [:jtk-dvlp.re-frame.tasks/running? :some-important-stuff])

        tasks
        (rf/subscribe [:jtk-dvlp.re-frame.tasks/tasks])]

    (fn []
      [:<>
       [:div "some app content"]

       [:ul "task list"
        ;; each task is a map with the original event vector plus name and id.
        (for [{:keys [:jtk-dvlp.re-frame.tasks/id] :as task} @tasks]
          ^{:key id}
          [:li task])]

       (when @block-ui?
         [:div "this div blocks the UI if there are running tasks"])])))

```

## Appendix

I´d be thankful to receive patches, comments and constructive criticism.

Hope the package is useful :-)
