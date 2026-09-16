[![Clojars Project](https://img.shields.io/clojars/v/jtk-dvlp/re-frame-tasks.svg)](https://clojars.org/jtk-dvlp/re-frame-tasks)
[![cljdoc badge](https://cljdoc.org/badge/jtk-dvlp/re-frame-tasks)](https://cljdoc.org/d/jtk-dvlp/re-frame-tasks/CURRENT)
[![License: EPL-2.0](https://img.shields.io/badge/License-EPL_2.0-red.svg)](https://github.com/jtkDvlp/re-frame-tasks/blob/master/LICENSE)

# Tasks interceptor / helpers for re-frame

Interceptors and helpers to register and unregister (background-)tasks
(FXs) in your app-state / app-db to list tasks and / or synchronize event
execution and single ui parts or the whole ui.

## Features

* register / unregister tasks / fxs via one line or global interceptor
  injection
  * support multiple and any fx on-completion keys via registration
* subscriptions for tasks list and running task boolean
  * running task boolean can be quick filtered by task name
* events to register / unregister tasks yourself
* helpers to register / unregister tasks into db yourself
* synchronize / queue event execution during running tasks via one line
  or global interceptor injection
* debounce event dispatches, with an effect to flush a pending one

Also works for async coeffect injections, see
https://github.com/jtkDvlp/re-frame-async-coeffects.

### What the namespace registers

Everything lives in `jtk-dvlp.re-frame.tasks`; the table uses `::` for
that namespace.

| Kind | Name | Purpose |
|---|---|---|
| Interceptor | `as-task` | marks an event and its effects as a task |
| Interceptor | `wait-for` | queues an event while tasks are running |
| Interceptor | `debounce` | collapses repeated dispatches of an event |
| Subscription | `::tasks` | all running tasks |
| Subscription | `::running?` | is anything running, optionally by name |
| Event | `::register` | register a task yourself |
| Event | `::unregister` | unregister a task and fire its after-events |
| Effect | `::dispatch-debounce` | dispatch an event debounced |
| Effect | `::flush-debounce` | run a pending debounced dispatch now |

## Getting started

### Get it / add dependency

Add the following dependency to your `project.clj`:<br>
[![Clojars Project](https://img.shields.io/clojars/v/jtk-dvlp/re-frame-tasks.svg)](https://clojars.org/jtk-dvlp/re-frame-tasks)

The library declares its runtime dependencies as `provided`, so your
project supplies them and their versions stay yours to pick. See
`project.clj :profiles :provided :dependencies` for the versions it is
built and tested against:

* `org.clojure/clojure`
* `re-frame`
* `com.taoensso/timbre`

### Usage

See api docs [![cljdoc badge](https://cljdoc.org/badge/jtk-dvlp/re-frame-tasks)](https://cljdoc.org/d/jtk-dvlp/re-frame-tasks/CURRENT)

For a working demo see `dev/jtk_dvlp/your_project.cljs`.

#### HTTP-Request as task via local interceptor

Register tasks with different names for different http-requests.

```clojure
(ns ^:figwheel-hooks jtk-dvlp.your-project
  (:require
    [day8.re-frame.http-fx :as http-fx]
    [jtk-dvlp.re-frame.tasks :as tasks]))

(tasks/reg-completion-keys-for-effect
  :http-xhrio :on-success :on-failure)

(re-frame/reg-event-fx :load-data-x
  [(tasks/as-task :loading-data-x [:http-xhrio])]
  (fn [_ [_ val]]
    {:http-xhrio
    {:method :get
     :uri "https://httpbin.org/get"
     :on-success [:load-data-x-success]
     :on-failure [:load-data-x-failure]}}))
```

#### HTTP-Request as task via global interceptor

Register every `http-xhrio` effect call as task with name
`:http-request`.

```clojure
(tasks/reg-completion-keys-for-effect
  :http-xhrio :on-success :on-failure)

(rf/reg-global-interceptor
  (tasks/as-task :http-request [:http-xhrio]))

(re-frame/reg-event-fx :load-data-x
  (fn [_ [_ val]]
    {:http-xhrio
    {:method :get
     :uri "https://httpbin.org/get"
     :on-success [:load-data-x-success]
     :on-failure [:load-data-x-failure]}}))
```

#### HTTP-Request with default on-success / on-failure handle as task

Sometimes more complex systems set default `on-success` or more often
`on-failure` handlers. Since the `as-task` interceptor wraps these
handlers aka completion-keys (see `reg-completion-keys-for-effect`) you
need to keep that in mind setting default handlers.

Fortunately you got some helpers for that.

```clojure
(rf/reg-fx :remote-request
  (fn [{:keys [on-success on-failure] :as request}]
    (let [request
          (assoc request
            :on-success (tasks/ensure-original-event
                          on-success :default-on-success)
            :on-failure (tasks/ensure-original-event
                          on-failure :default-on-failure))]

      ;; do some remote request stuff
      )))

(tasks/reg-completion-keys-for-effect
  :remote-request :on-success :on-failure)

(rf/reg-global-interceptor
  (tasks/as-task :remote-request [:remote-request]))
```

#### Visualise running tasks

To list your tasks or block the ui or block some ui container there are
subscriptions for you.

```clojure
(defn view
  []
  (let [block-ui?
        (rf/subscribe [::tasks/running?])

        tasks
        (rf/subscribe [::tasks/tasks])]

    (fn []
      [:<>
       [:ul "task list " (count @tasks)
        (doall
         (for [{:keys [::tasks/id] :as task} @tasks]
           ^{:key id}
           [:li [:pre (with-out-str (cljs.pprint/pprint task))]]))]

       (when @block-ui?
         [:div "this div blocks the UI if there are running tasks"])])))
```

#### Synchronize events via tasks

`wait-for` queues an event while the tasks it names are running and
dispatches it once they are done.

```clojure
(re-frame/reg-event-fx :load-data-based-on-x-n-y
  [(tasks/wait-for #{:loading-data-x :loading-data-y})]
  (fn [_ _]
    ;; use data-x and data-y
    ,,,))
```

To let a single dispatch through anyway, put it in the event's meta:

```clojure
(rf/dispatch
 ^{::tasks/wait-for {:ignore-tasks #{:loading-data-x}}}
 [:load-data-based-on-x-n-y])
```

#### Debounce event dispatches

Collapse a burst of dispatches into the last one -- a search field being
typed into, a resize handler, a slider.

```clojure
(re-frame/reg-event-fx :search
  [(tasks/debounce 300)]
  (fn [_ [_ term]]
    {:http-xhrio ,,,}))
```

`as-task` and `wait-for` take the same window as a trailing argument, so
you do not have to stack the interceptors yourself:

```clojure
;; as-task with name, effects, tasks to wait for and a debounce window
[(tasks/as-task :search [:http-xhrio] :any 300)]

;; wait-for with tasks and a debounce window
[(tasks/wait-for :any 300)]
```

A pending dispatch can be run immediately -- on submit, say, rather than
waiting out the window:

```clojure
{::tasks/flush-debounce {:dispatch [:search term]}}
```

#### Suspending a run and picking it up later

Some interceptors end an event run early and run it again later -- an
async coeffect waiting on a request, say. Nothing is in flight in
between, so a task would be completed the moment that first run ends, and
the wait it is meant to show would never appear.

Such an interceptor keeps the task by taking a **claim** on it. A task is
done once its last claim is given back; a monitored effect is simply one
of those claims. Whoever claims, releases -- in every outcome, the
failing one included.

```clojure
(rf/->interceptor
 :id ::my-suspending-interceptor

 :before
 (fn [context]
   (let [task-id (tasks/task-id context)
         event (rf/get-coeffect context :original-event)]

     (go
       (let [_result (<! (some-request))]
         ;; the continuation presents the token it was given
         (rf/dispatch (tasks/resume event task-id))))

     (-> context
         (tasks/claim ::my-claim)
         (update :queue empty)))))
```

`as-task` picks that same task up again instead of opening a second one,
and `wait-for` lets the event past the very task it continues. The
continuing run gives the claim back:

```clojure
(tasks/release context ::my-claim)
```

Where there is no run left to release in -- an error path -- there is an
event:

```clojure
(rf/dispatch [::tasks/release task-id ::my-claim])
```

Two rules make this work:

* **`as-task` has to come before the claiming interceptor.** It mints the
  token in its `:before`, so whoever runs earlier finds none. `claim`
  says so rather than inventing a task under a missing id.
* **`claim` and `release` note their intent in the context**, they never
  write to app-db. re-frame hands the event handler the db from the
  *coeffects*, so whatever an earlier `:before` put into the `:db` effect
  is gone the moment the handler runs.

## Upgrading from 2.x

3.0.0 is a breaking release.

| What changed | What to do |
|---|---|
| `set-completion-keys-per-effect!`, `merge-completion-keys-per-effect!` and `add-completion-keys-for-effect!` are gone | use `reg-completion-keys-for-effect`, one call per effect: `(tasks/reg-completion-keys-for-effect :http-xhrio :on-success :on-failure)` |
| A task carries what it still waits for under `::tasks/claims`, not `::effects` | the key holds the same monitored effects, plus claims taken by other interceptors -- see "Suspending a run" above |
| The interceptor ids are namespaced now: `::as-task`, `::wait-for` | adjust anything that removes or replaces them by id |
| A task carries its event under `::tasks/event` | it used to be `:event` |
| `::unregister-and-dispatch-original` is an event only; the effect of the same name is gone, and the event vector carries the effect key before the original event | use the `*-original-event` helpers instead of reading the vector by index |
| `re-frame`, `org.clojure/clojure` and the new `com.taoensso/timbre` are `provided` | add them to your own dependencies |

New in 3.0.0 and purely additive: the `debounce` interceptor, the
`::dispatch-debounce` and `::flush-debounce` effects, the trailing
`debounce-ms` argument on `as-task` and `wait-for`, and the
`::wait-for {:ignore-tasks ,,,}` event meta.

## Development

```
lein with-profile +test run -m cljs.main \
  --target node \
  --output-dir target/test \
  --output-to target/test/main.js \
  --compile-opts '{:main jtk-dvlp.re-frame.test-runner}' \
  --compile jtk-dvlp.re-frame.test-runner
node target/test/main.js
```

## Appendix

I´d be thankful to receive patches, comments and constructive criticism.

Hope the package is useful :-)
