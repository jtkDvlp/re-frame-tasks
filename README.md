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

```clojure
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

(tasks/set-completion-keys-per-effect!
 {:some-fx #{:on-complete}
  :some-other-fx #{:on-done :on-done-with-errors}})

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
  ;; give it a name and the fxs to monitor
  [(tasks/as-task :some-task [:some-fx [:fx 1]])
   ;; supports async coeffects
   (acoeffects/inject-acofx :some-acofx)]
  (fn [_ _]
    (println "handler")
    {;; modify your task via fx
     ::tasks/task
     {:this-is-some-task :data}

     :some-fx
     {:on-success [:some-event-success]
      :on-error [:some-event-error]
      :on-complete [:some-event-completed]
      ,,,}

     :some-other-fx
     {:on-done [:some-other-event-completed]
      ,,,}

     :fx
     [[:some-fx
       {:on-success [:some-event-success]
        :on-error [:some-event-error]
        :on-complete [:some-event-completed]
        ,,,}]

      ;; monitored fx referenced via path [:fx 1]
      [:some-other-fx
       {:on-done [:some-other-event-completed]}]]}))

;; error case, error within async coeffect
(rf/reg-event-fx :some-bad-event
  [(tasks/as-task :some-task [:some-fx :some-other-fx])
   (acoeffects/inject-acofx [:some-acofx :bad-data])]
  (fn [_ _]
    (println "handler")
    {:some-fx
     {:on-success [:some-event-success]
      :on-error [:some-event-error]
      :on-complete [:some-event-completed]
      ,,,}

     :some-other-fx
     {:on-done [:some-other-event-completed]
      ,,,}}))

;; error case, error within effect
(rf/reg-event-fx :some-other-bad-event
  [(tasks/as-task :some-task [:some-fx :some-other-fx])
   (acoeffects/inject-acofx :some-acofx)]
  (fn [_ _]
    (println "handler")
    {:some-fx
     {:on-success [:some-event-success]
      :on-error [:some-event-error]
      :on-complete [:some-event-completed]
      ,,,}

     :some-other-fx
     {:bad-data true
      :on-done [:some-other-event-completed]
      ,,,}}))

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
      [:p "open developer tools console for more infos."]

      [:<>
       [:button {:on-click #(rf/dispatch [:some-event])}
        "exec some event"]
       [:button {:on-click #(rf/dispatch [:some-bad-event])}
        "exec some bad event"]
       [:button {:on-click #(rf/dispatch [:some-other-bad-event])}
        "exec some other bad event"]

       [:ul "task list " (count @tasks)
        ;; task is a map of `::tasks/id`, `:name`, `:event and the
        ;; data you carry via `::task` fx from within the event
        (for [{:keys [::tasks/id] :as task} @tasks]
          ^{:key id}
          [:li [:pre (with-out-str (cljs.pprint/pprint task))]])]

       (when @block-ui?
         [:div "this div blocks the UI if there are running tasks"])])))
```

## Appendix

I´d be thankful to receive patches, comments and constructive criticism.

Hope the package is useful :-)
