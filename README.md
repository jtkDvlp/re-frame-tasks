[![Clojars Project](https://img.shields.io/clojars/v/jtk-dvlp/re-frame-tasks.svg)](https://clojars.org/jtk-dvlp/re-frame-tasks)
[![cljdoc badge](https://cljdoc.org/badge/jtk-dvlp/re-frame-tasks)](https://cljdoc.org/d/jtk-dvlp/re-frame-tasks/CURRENT)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://github.com/jtkDvlp/re-frame-tasks/blob/master/LICENSE)

# Tasks interceptor / helpers for re-frame

Interceptor and helpers to register and unregister (background-)tasks (FXs) in your app-state / app-db to list tasks and / or block single ui parts or the whole ui.

## Features

* register / unregister tasks / fxs via one line or global interceptor injection
  * support multiple and any fx on-completion keys via registration
* subscriptions for tasks list and running task boolean
  * running task boolean can be quick filtered by task name
* events to register / unregister tasks yourself
* helpers to register / unregister tasks into db yourself

Also works for async coeffect injections, see https://github.com/jtkDvlp/re-frame-async-coeffects.

## Getting started

### Get it / add dependency

Add the following dependency to your `project.clj`:<br>
[![Clojars Project](https://img.shields.io/clojars/v/jtk-dvlp/re-frame-tasks.svg)](https://clojars.org/jtk-dvlp/re-frame-tasks)

### Usage

See api docs [![cljdoc badge](https://cljdoc.org/badge/jtk-dvlp/re-frame-tasks)](https://cljdoc.org/d/jtk-dvlp/re-frame-tasks/CURRENT)

For more complex usage see working demo within project `dev/jtk_dvlp/your_project.cljs`

#### HTTP-Request as task via local interceptor

Register tasks with different names for different http-requests.

```clojure
(ns ^:figwheel-hooks jtk-dvlp.your-project
  (:require
    [day8.re-frame.http-fx :as http-fx]
    [jtk-dvlp.re-frame.tasks :as tasks]))

(tasks/set-completion-keys-per-effect!
  {:http-xhrio #{:on-success :on-failure})

(re-frame/reg-event-fx :load-data-x
  [(tasks/as-task :loading-data-x [:http-xhrio])]
  (fn [_ [_ val]]
    {:http-xhrio
    {:method :get
     :uri "https://httpbin.org/get"
     :on-success [:load-data-x-success]
     :on-failure [:load-data-x-failure]}}))

(re-frame/reg-event-fx :load-data-y
  [(tasks/as-task :loading-data-y [:http-xhrio])]
  (fn [_ [_ val]]
    {:http-xhrio
    {:method :get
     :uri "https://httpbin.org/get"
     :on-success [:load-data-y-success]
     :on-failure [:load-data-y-failure]}}))
```

#### HTTP-Request as task via global interceptor

Register every `http-xhrio` effect call as task with name `:http-request`.

```clojure
(ns ^:figwheel-hooks jtk-dvlp.your-project
  (:require
    [day8.re-frame.http-fx :as http-fx]
    [jtk-dvlp.re-frame.tasks :as tasks]))

(tasks/set-completion-keys-per-effect!
  {:http-xhrio #{:on-success :on-failure})

(rf/reg-global-interceptor
 (tasks/as-task :http-request [:http-xhrio]]))

(re-frame/reg-event-fx :load-data-x
  (fn [_ [_ val]]
    {:http-xhrio
    {:method :get
     :uri "https://httpbin.org/get"
     :on-success [:load-data-x-success]
     :on-failure [:load-data-x-failure]}}))

(re-frame/reg-event-fx :load-data-y
  (fn [_ [_ val]]
    {:http-xhrio
    {:method :get
     :uri "https://httpbin.org/get"
     :on-success [:load-data-y-success]
     :on-failure [:load-data-y-failure]}}))
```

#### HTTP-Request with default on-success / on-failure handle as task

Sometimes more complex systems set default `on-success` or more often `on-failure` handlers. Since the `as-task` inteceptor wraps these handlers aka completion-keys (see `tasks/set-completion-keys-per-effect`) you need to keep that in mind setting default handlers.

Fortunately you got some helpers for that.

```clojure
(ns ^:figwheel-hooks jtk-dvlp.your-project
  (:require
    [re-frame.core :as rf]
    [day8.re-frame.http-fx :as http-fx]
    [jtk-dvlp.re-frame.tasks :as tasks]))


(rf/reg-fx :remote-request
  (fn [{:keys [on-success on-failure] :as request}]
    (let [request
          (assoc request
            :on-success (tasks/ensure-original-event on-success :default-on-success)
            :on-failure (tasks/ensure-original-event on-failure :default-on-failure))]

      ;; do some remote request stuff
      )))

(tasks/set-completion-keys-per-effect!
  {:remote-request #{:on-success :on-failure})

(rf/reg-global-interceptor
 (tasks/as-task :remote-request [:remote-request]]))

(re-frame/reg-event-fx :load-data
  (fn [_ [_ val]]
    {:remote-request
     :uri "https://httpbin.org/get"
     :on-success [:load-data-success]}}))
```

#### Visualise running tasks

To list your tasks or block the ui or block some ui container there are subscriptions for you.

```clojure
(ns ^:figwheel-hooks jtk-dvlp.your-project
  (:require
    [re-frame.core :as rf]
    [jtk-dvlp.re-frame.tasks :as tasks]))


(defn view
  []
  (let [block-ui?
        (rf/subscribe [::tasks/running?])

        tasks
        (rf/subscribe [:tasks/tasks])]

    (fn []
      [:<>
       [:ul "task list " (count @tasks)
        (for [{:keys [::tasks/id] :as task} @tasks]
          ^{:key id}
          [:li [:pre (with-out-str (cljs.pprint/pprint task))]])]

       (when @block-ui?
         [:div "this div blocks the UI if there are running tasks"])])))

```

## Appendix

I´d be thankful to receive patches, comments and constructive criticism.

Hope the package is useful :-)
