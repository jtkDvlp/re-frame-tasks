[![CI](https://github.com/jtkDvlp/re-frame-tasks/actions/workflows/ci.yml/badge.svg)](https://github.com/jtkDvlp/re-frame-tasks/actions/workflows/ci.yml)
[![Clojars Project](https://img.shields.io/clojars/v/jtk-dvlp/re-frame-tasks.svg)](https://clojars.org/jtk-dvlp/re-frame-tasks)
[![cljdoc badge](https://cljdoc.org/badge/jtk-dvlp/re-frame-tasks)](https://cljdoc.org/d/jtk-dvlp/re-frame-tasks/CURRENT)
[![License](https://img.shields.io/badge/License-EPL%202.0-red.svg)](https://opensource.org/licenses/EPL-2.0)
[![paypal](https://www.paypalobjects.com/en_US/i/btn/btn_donate_SM.gif)](https://www.paypal.com/donate?hosted_button_id=2PDXQMHX56T6U)

# Tasks interceptor / helpers for re-frame

Interceptors and helpers that keep what is running in your app-db: named
tasks for long runners, a subscription to show them, and a way to hold
events back until they are done. ClojureScript, on top of
[re-frame](https://github.com/day8/re-frame).

See the [API docs](https://cljdoc.org/d/jtk-dvlp/re-frame-tasks/CURRENT)
for the full reference.

## The problem it solves

### Knowing what runs

Without it, "something is running" is a flag set and cleared by hand, in
every handler that starts or ends the work:

```clojure
(rf/reg-event-fx :load-data-x
  (fn [{:keys [db]} _]
    {:db (assoc db :loading-data-x? true)
     :http-xhrio {,,, :on-success [:load-data-x-success]
                      :on-failure [:load-data-x-failure]}}))

(rf/reg-event-db :load-data-x-success
  (fn [db [_ data]]
    (-> db (assoc :data-x data) (dissoc :loading-data-x?))))
```

Every further request brings its own flag, and every error path has to
remember to clear it.

Here an interceptor keeps the book and the handlers only do their work:

```clojure
(tasks/reg-completion-keys-for-effect
  :http-xhrio :on-success :on-failure)

(rf/reg-event-fx :load-data-x
  [(tasks/as-task :loading-data-x [:http-xhrio])]
  (fn [_ _]
    {:http-xhrio {,,, :on-success [:load-data-x-success]
                      :on-failure [:load-data-x-failure]}}))

(rf/reg-event-db :load-data-x-success
  (fn [db [_ data]] (assoc db :data-x data)))

;; and wherever it matters
@(rf/subscribe [::tasks/running? :loading-data-x])
```

The task stands from the moment the event runs until the effect reports
back -- on success and on failure alike.

### Keeping the order of things

The other half is sequence, and that is where an app's logic lives. Work
that must not start before other work has finished is everywhere: a
calculation over data that is still being fetched, a save while a reload
is in flight, a dialog that may only open once an import is through.

Without a registry of what runs, that order is wired by hand into
whoever finishes last:

```clojure
(rf/reg-event-fx :load-data-x-success
  (fn [{:keys [db]} [_ data]]
    {:db (assoc db :data-x data)
     ;; and now the loader has to know who comes after it
     :dispatch [:load-data-based-on-x-n-y]}))
```

A second source makes it worse: both handlers have to count what else is
still open before either may dispatch, and the order of the app ends up
spread over its loaders -- where nobody looks for it.

Here it is declared at the event that needs the data:

```clojure
(rf/reg-event-fx :load-data-based-on-x-n-y
  [(tasks/wait-for #{:loading-data-x :loading-data-y})]
  (fn [_ _]
    ;; use data-x and data-y
    ,,,))

;; dispatch it whenever -- it runs once both tasks are done, and right
;; away when neither of them is running
(rf/dispatch [:load-data-based-on-x-n-y])
```

The loaders know nothing about it, the order holds no matter who started
what, and the same interceptor registered globally holds the whole app
back while a task runs.

## Features

  * **A task per event, from one interceptor.** `as-task` marks an event
    and the effects it fires. The task is registered while they run and
    unregistered when the last one completes. Which keys of an effect
    report completion is registered per effect, so this works for any
    effect and not only for `:http-xhrio`.

  * **What runs, as data.** `::tasks` hands out the running tasks -- id,
    name, the event that opened each one and whatever the `::task` effect
    added. `::running?` answers the boolean, for everything or for one
    name.

  * **Events that wait for events.** `wait-for` queues an event while the
    tasks it names are running and lets it through once they are done.
    Injected globally it holds the whole app back, injected per event just
    that one; a single event opts out through its meta.

  * **Debouncing.** `debounce` collapses repeated dispatches of an event
    within a window, `::dispatch-debounce` does the same from a handler,
    and `::flush-debounce` runs a pending one right away.

  * **A run can be suspended.** `claim` keeps a task open past the end of
    its run, `resume` picks it up in a later one -- what an asynchronous
    coeffect needs to fetch its data without the task falling apart in
    between. See
    [re-frame-async-coeffects](https://github.com/jtkDvlp/re-frame-async-coeffects).

  * **Nothing the registry hides.** Registering, unregistering and
    attaching an after-event exist as plain db functions and as events, so
    everything the interceptors do you can do yourself.

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

### Add the dependency

Add the following dependency to your `project.clj`:<br>
[![Clojars Project](https://img.shields.io/clojars/v/jtk-dvlp/re-frame-tasks.svg)](https://clojars.org/jtk-dvlp/re-frame-tasks)

The library declares its runtime dependencies as `provided`, so your
project supplies them and their versions stay yours to pick. See
`project.clj :profiles :provided :dependencies` for the versions it is
built and tested against:

* `org.clojure/clojure`
* `re-frame`
* `reagent` -- re-frame declares it `provided` itself, so it does not
  arrive with re-frame

The library logs through re-frame's own loggers, so it needs nothing of
its own for that. Its messages carry a `re-frame-tasks:` prefix and its
tracing goes to the `:debug` logger, which a browser console hides until
you switch it to verbose. `re-frame.core/set-loggers!` redirects or
silences any of them.

### Usage

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
| `re-frame`, `org.clojure/clojure` and `reagent` are `provided` | add them to your own dependencies |
| The function form of `wait-for`'s `tasks` takes two arguments | it is `(fn [coeffects running-tasks] ,,,)` now, where it was `(fn [running-tasks] ,,,)` |

New in 3.0.0 and purely additive: the `debounce` interceptor, the
`::dispatch-debounce` and `::flush-debounce` effects, the trailing
`debounce-ms` argument on `as-task` and `wait-for`, and the
`::wait-for {:ignore-tasks ,,,}` event meta.

## Development

```bash
lein with-profile +test,-dev run -m cljs.main \
  --target node \
  --output-dir target/test \
  --output-to target/test/main.js \
  --compile-opts '{:main jtk-dvlp.re-frame.test-runner}' \
  --compile jtk-dvlp.re-frame.test-runner
node target/test/main.js
```

`+test,-dev` is the profile set that counts: `:test` brings the
ClojureScript compiler the library itself does not declare, and dropping
`:dev` keeps figwheel and reagent's React shim out, so the library is
built against what a consumer actually gets.

That run and an `:advanced` compile of the library happen on every push
and pull request, see
[`.github/workflows/test.yml`](.github/workflows/test.yml). Both reject a
compiler warning, because an unknown var is only a warning to
`cljs.main` and would otherwise pass.

What has changed is in [`CHANGELOG.md`](CHANGELOG.md); what is merged but
not released yet stands in the open release pull request.

## Contributing

### Commit messages

Commit subjects follow [Conventional
Commits](https://www.conventionalcommits.org/en/v1.0.0/):

```
<type>[(<scope>)][!]: <description>
```

The `!` marks a breaking change and belongs to the type, not to `feat` --
`fix!:` is just as valid and means a bug fix that breaks.

Pull requests are merged, not squashed, so every commit of a branch ends
up on `master` -- the convention applies to each of them, not just to the
pull request title. A CI job checks this on every pull request.

The type decides the next version:

| Subject | Release |
|---|---|
| `fix: ...` | patch -- `3.0.0` → `3.0.1` |
| `feat: ...` | minor -- `3.0.0` → `3.1.0` |
| any type with a `!`, or a `BREAKING CHANGE:` footer | major -- `3.0.0` → `4.0.0` |
| `perf:`, `revert:`, `refactor:`, `docs:` | patch -- they reach a user, as behaviour or as the documentation that ships with the artifact |
| `build:`, `chore:`, `ci:`, `style:`, `test:` | none -- they act inside the repository |

### Releasing

Releasing is automatic; nobody edits a version number by hand.

1. A merge to `master` lets
   [release-please](https://github.com/googleapis/release-please) open or
   update a release pull request. It carries the next version in
   `project.clj` and the changelog entries derived from the commits since
   the last release.
2. Merging that pull request creates the git tag and the GitHub release.
3. The same workflow run then tests the tagged state and pushes the
   artifact to Clojars.

So the release pull request is the point where a release is decided --
until it is merged, nothing leaves the house.

## Appendix

I´d be thankful to receive patches, comments and constructive criticism.

Hope the package is useful :-)
