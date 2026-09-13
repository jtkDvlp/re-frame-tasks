(defproject jtk-dvlp/re-frame-tasks "3.0.0-SNAPSHOT"
  :description
  "re-frame interceptors to introduce tasks, synchronize event flow and debounce event dispatches"

  :url
  "https://github.com/jtkDvlp/re-frame-tasks"

  :license
  {:name
   "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"

   :url
   "https://www.eclipse.org/legal/epl-2.0/"}

  :plugins
  [[lein-ancient "0.7.0"]]

  :source-paths
  ["src"]

  :profiles
  {:provided
   {:dependencies
    [[org.clojure/clojure "1.12.5"]
     [com.taoensso/timbre "6.8.0"]
     [re-frame "1.4.7"]]}

   :dev
   {:dependencies
    [[com.bhauman/figwheel-main "0.2.20"]

     [reagent "2.0.1"]
     [cljsjs/react "18.3.1-1"]
     [cljsjs/react-dom "18.3.1-1"]

     [org.clojure/core.async "1.9.865"]
     [jtk-dvlp/core.async-helpers "3.5.0"]

     [net.clojars.jtkdvlp/re-frame-async-coeffects "2.0.0"]]

    :source-paths
    ["dev"]

    :resource-paths
    ["target"]}

   ;; NOTE: The library itself declares no ClojureScript dependency -- a
   ;; consumer brings their own. The test run needs a compiler.
   ;; WATCHOUT: Not the 1.10.773 that figwheel-main drags in. re-frame
   ;; 1.4.7 calls `update-vals` in `re-frame.flow.alpha`, which arrived
   ;; in 1.11 -- against the older compiler every build warns about an
   ;; undeclared var.
   :test
   {:dependencies
    [[org.clojure/clojurescript "1.12.145"]]

    :source-paths
    ["test"]}

   :repl
   {:dependencies
    [[cider/piggieback "0.7.0"]]

    :repl-options
    {:nrepl-middleware
     [cider.piggieback/wrap-cljs-repl]

     :init-ns
     user

     :init
     (fig-init)}}}

  ,,,)
