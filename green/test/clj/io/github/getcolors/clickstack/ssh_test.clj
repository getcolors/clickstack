(ns io.github.getcolors.clickstack.ssh-test
 (:require [clojure.test :refer [deftest is]]
 [io.github.getcolors.clickstack.ssh :as ssh]
 [io.github.getcolors.clickstack.validate-test :refer [fixture optout]]))
(deftest build-managed-identity
 (is (= "/home/build-placeholder/.ssh/clickstack-fixture" (:ssh-private-key-path (ssh/with-machine-key (fixture :green/event :build))))))
(deftest external-identity-preserved
 (is (= (optout) (ssh/with-machine-key (optout))))
 (is (= "/home/build-placeholder/.ssh/operator-key" (second (ssh/identity-args (optout))))))
(deftest no-application-key-generation
 (is (= (fixture) (ssh/with-machine-key (fixture)))))
