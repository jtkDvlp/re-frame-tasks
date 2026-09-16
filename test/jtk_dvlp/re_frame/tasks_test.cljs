(ns jtk-dvlp.re-frame.tasks-test
  "Tests for the task registry, the original-event helpers and the
   interceptors `as-task`, `wait-for` and `debounce`."
  (:require
   [cljs.test :refer [deftest testing is are use-fixtures]
    :refer-macros [async]]

   [re-frame.core :as rf]
   [re-frame.db :as rf-db]

   [jtk-dvlp.re-frame.tasks :as tasks]))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers

(defn- task
  ([name]
   (task name (random-uuid)))

  ([name id]
   {::tasks/id id, :name name}))

(defn- db-with
  [& tasks]
  (reduce tasks/register {} tasks))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Registry

(deftest register-and-unregister
  (let [loading
        (task :loading)]

    (testing "a registered task is found by id and by task map"
      (let [db (db-with loading)]
        (is (= loading (tasks/get-task db (::tasks/id loading))))
        (is (= loading (tasks/get-task db loading)))
        (is (= [loading] (vec (tasks/get-tasks db))))))

    (testing "unregistering removes it again"
      (let [db (-> (db-with loading)
                   (tasks/unregister loading))]
        (is (nil? (tasks/get-task db loading)))
        (is (empty? (tasks/get-tasks db)))))))

(deftest get-task-by-name-returns-the-task
  ;; This one exists because the function returned `true` rather than
  ;; the task: every caller reading a key off the result got nil, and
  ;; `running?` looked fine throughout because it only ever checks for
  ;; something non-nil.
  (let [loading
        (task :loading)

        db
        (db-with loading)]

    (is (= loading (tasks/get-task-by-name db :loading)))
    (is (nil? (tasks/get-task-by-name db :unknown)))))

(deftest running?-answers-per-name
  (let [db (db-with (task :loading))]
    (are [expected actual] (= expected actual)
      true  (tasks/running? db)
      true  (tasks/running? db :loading)
      false (tasks/running? db :something-else)
      false (tasks/running? {}))))

(deftest attached-events-survive-until-unregister
  (let [loading
        (task :loading)

        db
        (-> (db-with loading)
            (tasks/attach-after-event loading [:done-1])
            (tasks/attach-after-event loading [:done-2]))]

    (is (= [[:done-1] [:done-2]]
           (-> db
               (tasks/get-task loading)
               (::tasks/after-events))))))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Original events

(deftest original-event-helpers
  (let [original
        [:my-event 42]

        ;; NOTE: The layout of a task event is
        ;; [::unregister-and-dispatch-original task effect original-event].
        ;; The original event sits at index 3; it was index 2 up to 2.x.
        task-event
        [:jtk-dvlp.re-frame.tasks/unregister-and-dispatch-original
         (task :loading) :http-xhrio original]]

    (testing "a plain event is its own original event"
      (is (false? (tasks/task-event? original)))
      (is (= original (tasks/get-original-event original)))
      (is (true? (tasks/some-original-event? original))))

    (testing "a task event carries the original event"
      (is (true? (tasks/task-event? task-event)))
      (is (= original (tasks/get-original-event task-event))))

    (testing "assoc, update and ensure only touch task events"
      (is (= [:other] (tasks/get-original-event
                       (tasks/assoc-orignal-event task-event [:other]))))
      (is (= original (tasks/assoc-orignal-event original [:other])))
      (is (= [:my-event 43] (tasks/get-original-event
                             (tasks/update-original-event
                              task-event update 1 inc))))
      (is (= task-event (tasks/ensure-original-event task-event [:fallback])))
      (is (= [:fallback] (tasks/get-original-event
                          (tasks/ensure-original-event
                           (assoc task-event 3 nil) [:fallback])))))))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Interceptors

(use-fixtures :each
  {:before
   (fn []
     (reset! rf-db/app-db {}))})

(def ^:private queue-drain-ms
  "How long to give re-frame's event queue before asserting. Its router
   dispatches via `goog.async.nextTick`, so a single macrotask is already
   generous; 50 ms leaves room for a slow CI runner."
  50)

(defn- when-queue-drained
  [f]
  (js/setTimeout f queue-drain-ms))

(deftest as-task-registers-a-task-and-completes-it
  (async done
    (let [effect-args
          (atom nil)]

      (tasks/reg-completion-keys-for-effect ::probe-fx :on-success)
      (rf/reg-fx ::probe-fx (partial reset! effect-args))

      (rf/reg-event-db ::loaded
        (fn [db _] (assoc db ::loaded? true)))

      (rf/reg-event-fx ::load
        [(tasks/as-task :loading [::probe-fx])]
        (fn [_ _] {::probe-fx {:on-success [::loaded]}}))

      (rf/dispatch-sync [::load])

      (testing "the task is registered while the effect is in flight"
        (is (true? (tasks/running? @rf-db/app-db :loading))))

      (testing "the completion key is wrapped, carrying the original event"
        (let [on-success (:on-success @effect-args)]
          (is (true? (tasks/task-event? on-success)))
          (is (= [::loaded] (tasks/get-original-event on-success)))))

      (rf/dispatch (:on-success @effect-args))

      (when-queue-drained
        (fn []
          (testing "completing the effect runs the original event"
            (is (true? (::loaded? @rf-db/app-db))))

          (testing "and unregisters the task"
            (is (false? (tasks/running? @rf-db/app-db :loading))))

          (done))))))

(deftest as-task-without-effects-unregisters-immediately
  (rf/reg-event-fx ::no-effects
    [(tasks/as-task :instant)]
    (fn [_ _] {}))

  (rf/dispatch-sync [::no-effects])
  (is (false? (tasks/running? @rf-db/app-db :instant))))

(deftest wait-for-delays-an-event-until-the-task-completes
  (async done
    (let [effect-args
          (atom nil)]

      (tasks/reg-completion-keys-for-effect ::probe-fx :on-success)
      (rf/reg-fx ::probe-fx (partial reset! effect-args))

      (rf/reg-event-fx ::long-running
        [(tasks/as-task :long-running [::probe-fx])]
        (fn [_ _] {::probe-fx {:on-success [::irrelevant]}}))

      (rf/reg-event-db ::irrelevant (fn [db _] db))

      (rf/reg-event-db ::follow-up
        [(tasks/wait-for :long-running)]
        (fn [db _] (assoc db ::followed-up? true)))

      (rf/dispatch-sync [::long-running])
      (rf/dispatch-sync [::follow-up])

      (testing "the follow-up does not run while the task is running"
        (is (nil? (::followed-up? @rf-db/app-db))))

      (rf/dispatch (:on-success @effect-args))

      (when-queue-drained
        (fn []
          (testing "it runs once the task completes"
            (is (true? (::followed-up? @rf-db/app-db))))
          (done))))))

(deftest as-task-works-as-a-global-interceptor
  (async done
    (tasks/reg-completion-keys-for-effect ::probe-fx :on-success)
    (rf/reg-fx ::probe-fx (constantly nil))

    (rf/reg-event-fx ::globally-tracked
      (fn [_ _] {::probe-fx {:on-success [::irrelevant]}}))

    (rf/reg-global-interceptor (tasks/as-task :global [::probe-fx]))

    (try
      (rf/dispatch-sync [::globally-tracked])
      (is (true? (tasks/running? @rf-db/app-db :global)))
      (finally
        (rf/clear-global-interceptor :jtk-dvlp.re-frame.tasks/as-task)
        (when-queue-drained done)))))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Debounce

(def ^:private debounce-window-ms
  "Short enough to keep the suite quick, long enough that three dispatches
   in a row land inside it even on a loaded machine."
  30)

(def ^:private idle-debounce-window-ms
  "Deliberately longer than any test waits. An event debounced with this
   can only run because something flushed it."
  5000)

(deftest debounce-collapses-repeated-dispatches
  (async done
    (let [runs
          (atom 0)]

      (rf/reg-event-db ::debounced
        [(tasks/debounce debounce-window-ms)]
        (fn [db _] (swap! runs inc) db))

      (dotimes [_ 3]
        (rf/dispatch [::debounced]))

      (js/setTimeout
       (fn []
         (is (= 1 @runs) "three dispatches inside the window run once")
         (done))
       (* 5 debounce-window-ms)))))

(deftest flush-debounce-runs-the-event-now
  (async done
    (let [runs
          (atom 0)]

      (rf/reg-event-db ::flushable
        [(tasks/debounce idle-debounce-window-ms)]
        (fn [db _] (swap! runs inc) db))

      (rf/dispatch [::flushable])

      (when-queue-drained
        (fn []
          (is (zero? @runs) "still waiting out its window")

          (tasks/flush-debounce {:dispatch [::flushable]})

          (when-queue-drained
            (fn []
              (is (= 1 @runs) "flushing runs it without waiting")
              (done))))))))

(deftest flush-debounce-is-also-an-effect
  (async done
    (let [runs
          (atom 0)]

      (rf/reg-event-db ::flushable-by-fx
        [(tasks/debounce idle-debounce-window-ms)]
        (fn [db _] (swap! runs inc) db))

      (rf/reg-event-fx ::flush-it
        (fn [_ _]
          {::tasks/flush-debounce {:dispatch [::flushable-by-fx]}}))

      (rf/dispatch [::flushable-by-fx])

      (when-queue-drained
        (fn []
          (rf/dispatch [::flush-it])
          (when-queue-drained
            (fn []
              (is (= 1 @runs) "the effect flushes the pending dispatch")
              (done))))))))

(deftest wait-for-still-waits-when-given-a-debounce-window
  ;; The sugar folds a second interceptor into the one `wait-for` returns.
  ;; Both behaviours have to survive that: debouncing must not cost the
  ;; waiting, which is what an over-eager composition silently does.
  (async done
    (let [effect-args
          (atom nil)]

      (tasks/reg-completion-keys-for-effect ::probe-fx :on-success)
      (rf/reg-fx ::probe-fx (partial reset! effect-args))

      (rf/reg-event-fx ::blocker
        [(tasks/as-task :blocker [::probe-fx])]
        (fn [_ _] {::probe-fx {:on-success [::irrelevant]}}))

      (rf/reg-event-db ::irrelevant (fn [db _] db))

      (rf/reg-event-db ::debounced-follow-up
        [(tasks/wait-for :blocker debounce-window-ms)]
        (fn [db _] (assoc db ::followed-up? true)))

      (rf/dispatch-sync [::blocker])
      (rf/dispatch [::debounced-follow-up])

      (js/setTimeout
       (fn []
         (is (nil? (::followed-up? @rf-db/app-db))
             "the debounce window passed, but the task still blocks")

         (rf/dispatch (:on-success @effect-args))

         (when-queue-drained
          (fn []
            (is (true? (::followed-up? @rf-db/app-db))
                "and it runs once the task completes")
            (done))))
       (* 5 debounce-window-ms)))))

(deftest as-task-takes-tasks-to-wait-for-and-a-debounce-window
  (let [interceptor
        (tasks/as-task :search [::probe-fx] :any debounce-window-ms)]

    (is (= :jtk-dvlp.re-frame.tasks/as-task (:id interceptor))
        "the sugar folds into one interceptor")
    (is (some? (:before interceptor)) "carrying the folded-in before")
    (is (some? (:after interceptor)) "and its own after")))

(deftest wait-for-can-be-ignored-per-event
  (async done
    (let [effect-args
          (atom nil)]

      (tasks/reg-completion-keys-for-effect ::probe-fx :on-success)
      (rf/reg-fx ::probe-fx (partial reset! effect-args))

      (rf/reg-event-fx ::blocking
        [(tasks/as-task :blocking [::probe-fx])]
        (fn [_ _] {::probe-fx {:on-success [::irrelevant]}}))

      (rf/reg-event-db ::irrelevant (fn [db _] db))

      (rf/reg-event-db ::ignores-the-block
        [(tasks/wait-for :blocking)]
        (fn [db _] (assoc db ::got-through? true)))

      (rf/dispatch-sync [::blocking])
      (rf/dispatch-sync
       (with-meta [::ignores-the-block]
         {::tasks/wait-for {:ignore-tasks #{:blocking}}}))

      (is (true? (::got-through? @rf-db/app-db))
          "the named task is ignored for this one event")

      (when-queue-drained done))))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Claims

(def ^:private suspender-claim
  "What the stand-in below calls its claim. A real suspending interceptor
   uses something unique per suspension, so two of them on one event do
   not collapse into a single claim."
  ::suspender)

(defn- suspender
  "Stands in for an interceptor that ends the run early and continues it
   later -- an async coeffect, typically. Records what it would need to
   come back with, claims the task and aborts."
  [!handover]
  (rf/->interceptor
   :id ::suspender

   :before
   (fn [context]
     (reset! !handover
             {:task-id (tasks/task-id context)
              :event (get-in context [:coeffects :original-event])})
     (-> context
         (tasks/claim suspender-claim)
         (update :queue empty)))))

(defn- resumer
  "The other half: a run that gives the claim back."
  []
  (rf/->interceptor
   :id ::resumer
   :before #(tasks/release % suspender-claim)))

(deftest a-claimed-task-outlives-a-run-without-effects
  (let [!handover
        (atom nil)]

    (rf/reg-event-db ::suspends
      [(tasks/as-task :suspendable) (suspender !handover)]
      (fn [db _] db))

    (rf/dispatch-sync [::suspends])

    (testing "the run ended without effects, yet the task stands"
      (is (true? (tasks/running? @rf-db/app-db :suspendable))))

    (testing "and the claimer got the token it needs to come back"
      (is (some? (:task-id @!handover)))
      (is (= (:task-id @!handover)
             (-> @rf-db/app-db
                 (tasks/get-task-by-name :suspendable)
                 (::tasks/id)))))))

(deftest resuming-picks-the-task-up-instead-of-opening-a-second
  (let [!handover
        (atom nil)]

    (rf/reg-event-db ::suspends-once
      [(tasks/as-task :suspendable) (suspender !handover)]
      (fn [db _] db))

    (rf/dispatch-sync [::suspends-once])

    (let [{:keys [task-id event]}
          @!handover]

      ;; The continuation runs the same event again, now releasing rather
      ;; than claiming -- as a real one would, having its data in hand.
      (rf/reg-event-db ::suspends-once
        [(tasks/as-task :suspendable) (resumer)]
        (fn [db _] db))

      (rf/dispatch-sync (tasks/resume event task-id))

      (testing "no second task was opened"
        (is (>= 1 (count (tasks/get-tasks @rf-db/app-db)))))

      (testing "and the released task is done"
        (is (false? (tasks/running? @rf-db/app-db :suspendable)))))))

(deftest a-claim-can-be-given-back-from-outside-a-run
  (async done
    (let [!handover
          (atom nil)]

      (rf/reg-event-db ::suspends-and-fails
        [(tasks/as-task :suspendable) (suspender !handover)]
        (fn [db _] db))

      (rf/dispatch-sync [::suspends-and-fails])
      (is (true? (tasks/running? @rf-db/app-db :suspendable)))

      ;; The error path has no run to hand the claim back in.
      (rf/dispatch [::tasks/release (:task-id @!handover) suspender-claim])

      (when-queue-drained
       (fn []
         (is (false? (tasks/running? @rf-db/app-db :suspendable))
             "releasing the last claim completes the task")
         (done))))))

(deftest wait-for-lets-the-continuation-past-its-own-task
  (let [!handover
        (atom nil)]

    (rf/reg-event-db ::suspends-and-blocks
      [(tasks/as-task :suspendable) (suspender !handover)]
      (fn [db _] db))

    (rf/reg-event-db ::waits
      [(tasks/wait-for :suspendable)]
      (fn [db _] (assoc db ::waited? true)))

    (rf/dispatch-sync [::suspends-and-blocks])

    (testing "an unrelated event waits for the suspended task"
      (rf/dispatch-sync [::waits])
      (is (nil? (::waited? @rf-db/app-db))))

    (testing "its own continuation does not"
      (rf/reg-event-db ::suspends-and-blocks
        [(tasks/as-task :suspendable) (resumer)]
        (fn [db _] (assoc db ::continued? true)))

      (rf/dispatch-sync
       (tasks/resume (:event @!handover) (:task-id @!handover)))

      (is (true? (::continued? @rf-db/app-db))))))

(deftest claiming-without-a-task-changes-nothing
  ;; The claim has to come after `as-task`, otherwise there is no task yet.
  ;; Getting that wrong must not invent one under a nil id.
  (let [!handover
        (atom nil)]

    (rf/reg-event-db ::claims-too-early
      [(suspender !handover) (tasks/as-task :never-claimed)]
      (fn [db _] db))

    (rf/dispatch-sync [::claims-too-early])

    (is (nil? (:task-id @!handover)))
    (is (empty? (tasks/get-tasks @rf-db/app-db)))))
