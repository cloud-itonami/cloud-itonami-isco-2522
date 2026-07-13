(ns sysadmin.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [sysadmin.store :as store]
            [sysadmin.governor :as governor]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Mise Systems"})
    (store/register-system! st {:system-id "sys-1" :client-id "client-1" :name "web"})
    ;; approved maintenance window 02:00-04:00 (120-240)
    (store/register-window! st {:window-id "w-1" :system-id "sys-1" :start 120 :end 240})
    ;; change freeze 03:00-05:00 (180-300)
    (store/register-freeze! st {:freeze-id "f-1" :client-id "client-1"
                                :start 180 :end 300 :reason "release week"})
    st))

(defn- change [op s e]
  {:op op :effect :propose :system-id "sys-1" :at-start s :at-end e
   :confidence 0.9 :stake :medium})

(def ^:private req {:client-id "client-1"})

(deftest apply-inside-window-outside-freeze-escalates-only
  (testing "02:00-02:50 is inside the window and clear of the freeze:
            no HARD violation, but applying always needs a human"
    (let [st (fresh-store)
          v (governor/check req {} (change :apply-change 120 170) st)]
      (is (not (:hard? v)))
      (is (:escalate? v)))))

(deftest hard-on-unregistered-client
  (let [st (fresh-store)
        v (governor/check {:client-id "nobody"} {} (change :apply-change 120 170) st)]
    (is (:hard? v))
    (is (some #(= :no-client (:rule %)) (:violations v)))))

(deftest hard-on-no-actuation-violation
  (let [st (fresh-store)
        v (governor/check req {} (assoc (change :apply-change 120 170)
                                        :effect :direct-write) st)]
    (is (:hard? v))
    (is (some #(= :no-actuation (:rule %)) (:violations v)))))

(deftest hard-on-invented-system
  (let [st (fresh-store)
        v (governor/check req {} (assoc (change :apply-change 120 170)
                                        :system-id "sys-ghost") st)]
    (is (:hard? v))
    (is (some #(= :unknown-system (:rule %)) (:violations v)))))

(deftest hard-on-outside-window
  (testing "urgency does not create a window"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (change :apply-change 600 660)
                                          :confidence 0.99) st)]
      (is (:hard? v))
      (is (some #(= :outside-window (:rule %)) (:violations v))))))

(deftest hard-on-window-straddling-interval
  (testing "half in the window is not in the window (containment, not overlap)"
    (let [st (fresh-store)
          v (governor/check req {} (change :apply-change 100 170) st)]
      (is (:hard? v))
      (is (some #(= :outside-window (:rule %)) (:violations v))))))

(deftest hard-on-freeze-violation
  (testing "inside the window but overlapping the freeze: still held —
            edit the freeze record first, on the record"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (change :apply-change 170 230)
                                          :confidence 0.99) st)]
      (is (:hard? v))
      (is (some #(= :freeze-violation (:rule %)) (:violations v))))))

(deftest hard-on-invalid-interval
  (let [st (fresh-store)
        v (governor/check req {} (change :apply-change 200 200) st)]
    (is (:hard? v))
    (is (some #(= :invalid-interval (:rule %)) (:violations v)))))

(deftest draft-change-is-ok-without-window
  (testing "drafting (not applying) needs no window"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (change :draft-change 600 660)
                                          :stake :low) st)]
      (is (:ok? v)))))

(deftest escalates-credential-rotation
  (let [st (fresh-store)
        v (governor/check req {} {:op :rotate-credentials :effect :propose
                                  :system-id "sys-1" :confidence 0.9 :stake :high} st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))

(deftest escalates-low-confidence
  (let [st (fresh-store)
        v (governor/check req {} (assoc (change :draft-change 130 140)
                                        :confidence 0.3 :stake :low) st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))
