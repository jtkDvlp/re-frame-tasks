(ns jtk-dvlp.re-frame.test-runner
  "Entry point for the ClojureScript test run under node."
  (:require
   [cljs.test :refer [run-tests] :refer-macros [run-tests]]

   [re-frame.core :as rf]

   [jtk-dvlp.re-frame.tasks-test]))

(defmethod cljs.test/report [:cljs.test/default :end-run-tests]
  [{:keys [fail error] :as summary}]
  (println "\n" (pr-str summary))
  ;; WATCHOUT: cljs.test does not set an exit code on its own. Without
  ;; this the CI job would pass on a red suite.
  (when (pos? (+ fail error))
    (set! (.-exitCode js/process) 1)))

(defn- readable-loggers!
  []
  ;; NOTE: re-frame logs cljs data through `console`, which node renders as
  ;; raw JS objects -- unreadable. `pr-str` makes a warning legible enough
  ;; to act on.
  (let [log (fn [& args] (println (apply pr-str args)))]
    (rf/set-loggers!
     {:log log, :warn log, :error log, :debug log, :group log
      :groupEnd (fn [& _])})))

(defn -main
  [& _]
  (readable-loggers!)
  (run-tests 'jtk-dvlp.re-frame.tasks-test))

;; WATCHOUT: The node target only calls `-main` when this is set. Without
;; it the compiled bundle loads every namespace, runs nothing, and exits
;; 0 -- a green CI that never tested anything.
(set! *main-cli-fn* -main)
