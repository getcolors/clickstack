(ns io.github.getcolors.clickstack.workflow-test
 (:require [clojure.test :refer [deftest is]]
 [io.github.getcolors.clickstack.workflow :as workflow]
 [io.github.getcolors.clickstack.compute :as compute]
 [io.github.getcolors.clickstack.validate-test :refer [fixture optout do-fixture do-optout]]))
(deftest offline-start
 (doseq [f [fixture optout do-fixture do-optout]]
  (is (= 0 (:green/exit (workflow/start-step (assoc (f) :green/event :build) {}))))))
(deftest singleton-library-contract
 (is (= [{:role nil :count 1}] compute/topology))
 (is (= ["clickstack-fixture/clickstack-infrastructure.tfstate"] (:legacy_state_keys (compute/requirements (fixture))))))
(deftest errors-and-observed-nodes
 (is (= "legacy compute state requires migration" (:green/err (compute/attach (fixture) {:status "error" :errors ["legacy compute state requires migration"]}))))
 (is (= "ubuntu" (:user (compute/attach (fixture) {:status "present" :cluster {:nodes [{:ip "203.0.113.7" :user "ubuntu"}]}}))))
 (is (:clickstack/already-destroyed (compute/attach (fixture) {:status "destroyed"}))))

(deftest library-document-keys-render-deterministically
 (is (= (#'io.github.getcolors.clickstack.compute/compute-json {"a" 0 :b 1} 0)
        (#'io.github.getcolors.clickstack.compute/compute-json {:a 0 "b" 1} 0))))
