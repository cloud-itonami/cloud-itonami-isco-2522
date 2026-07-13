(ns sysadmin.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [sysadmin.actor :as actor]
            [sysadmin.store :as store]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Mise Systems"})
    (store/register-system! st {:system-id "sys-1" :client-id "client-1" :name "web"})
    (store/register-window! st {:window-id "w-1" :system-id "sys-1" :start 120 :end 240})
    st))

(deftest commits-a-draft-change
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :draft-change :stake :low
                 :system-id "sys-1" :at-start 130 :at-end 160}
        result (actor/run-request! graph request {} "thread-1")]
    (is (= :done (:status result)))
    (is (some? (get-in result [:state :record])))
    (is (= 1 (count (store/records-of st "client-1"))))))

(deftest holds-an-out-of-window-apply
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :apply-change :stake :high
                 :system-id "sys-1" :at-start 600 :at-end 660}
        result (actor/run-request! graph request {} "thread-2")]
    (is (= :hold (:disposition (:state result))))
    (is (empty? (store/records-of st "client-1")))))

(deftest interrupts-then-applies-in-window-on-human-approval
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :apply-change :stake :high
                 :system-id "sys-1" :at-start 130 :at-end 170}
        interrupted (actor/run-request! graph request {} "thread-3")]
    (is (= :interrupted (:status interrupted)))
    (is (empty? (store/records-of st "client-1")))
    (let [resumed (actor/approve! graph "thread-3")]
      (is (= :done (:status resumed)))
      (is (= 1 (count (store/records-of st "client-1")))))))
