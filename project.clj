(defproject jtk-dvlp/re-frame-tasks "1.0.0-SNAPSHOT"
  :description
  "A re-frame interceptor and helpers to register / unregister (background-)tasks"

  :url
  "https://github.com/jtkDvlp/re-frame-tasks"

  :license
  {:name
   "MIT"

   :url
   "https://github.com/jtkDvlp/budgetbook/blob/master/LICENSE"}

  :source-paths
  ["src"]

  :target-path
  "target"

  :clean-targets
  ^{:protect false}
  [:target-path]

  :profiles
  {:provided
   {:dependencies
    [[org.clojure/clojure "1.10.0"]
     [org.clojure/clojurescript "1.10.773"]

     [re-frame "0.12.0"]]}

   :dev
   {:dependencies
    [[com.bhauman/figwheel-main "0.2.7"]]

    :source-paths
    ["dev"]}

   :repl
   {:dependencies
    [[cider/piggieback "0.5.0"]]

    :repl-options
    {:nrepl-middleware
     [cider.piggieback/wrap-cljs-repl]

     :init-ns
     user}}}

  ,,,)
