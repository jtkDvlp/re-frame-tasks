;; WATCHOUT: Die Version pflegt release-please, nicht die Hand. Die
;; Anmerkung `x-release-please-version` ist das, woran es die Zeile
;; findet -- ohne sie wird nur das Changelog fortgeschrieben und das
;; Paket trägt weiter die alte Zahl.
(defproject jtk-dvlp/re-frame-tasks "2.2.0" ; x-release-please-version
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

  :deploy-repositories
  [["clojars"
    {:url
     "https://repo.clojars.org/"

     ;; WATCHOUT: Die Zugangsdaten stehen als Repository-Secrets und
     ;; kommen über Umgebungsvariablen in den Build -- nie als Datei im
     ;; Repo, auch nicht als ignorierte.
     :username
     :env/clojars_username

     :password
     :env/clojars_password

     ;; NOTE: Im Lauf liegt kein Signaturschlüssel, und Clojars verlangt
     ;; keine Signatur.
     :sign-releases
     false}]]

  :target-path
  "target"

  :clean-targets
  ^{:protect false}
  [:target-path]

  :dependencies
  [[org.clojure/clojure "1.10.0"]
   [org.clojure/clojurescript "1.10.773"]
   [jtk-dvlp/core.async-helpers "3.2.0"]
   [re-frame "1.1.2"]]

  :profiles
  {:dev
   {:dependencies
    [[com.bhauman/figwheel-main "0.2.7"]
     [org.clojure/core.async "1.3.610"]
     [net.clojars.jtkdvlp/re-frame-async-coeffects "2.0.0"]]

    :source-paths
    ["dev"]}

   :repl
   {:dependencies
    [[cider/piggieback "0.5.0"]]

    :repl-options
    {:nrepl-middleware
     [cider.piggieback/wrap-cljs-repl]

     :init-ns
     user

     :init
     (fig-init)}}}

  ,,,)
