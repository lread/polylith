(ns polylith.clj.core.change.environment-test
  (:require [clojure.test :refer :all]
            [polylith.clj.core.change.environment :as env]))

(def environments [{:name "cli"
                    :component-names ["util" "validator"]
                    :base-names ["cli"]}
                   {:name "core"
                    :component-names ["change" "util" "validator" "workspace"]
                    :base-names []}
                   {:name "dev"
                    :component-names ["util" "validator" "workspace"]
                    :base-names ["cli"]}])

(def changed-bricks #{"workspace" "cmd" "core"})

(deftest changed-environments--when-two-of-three-environments-contain-changed-bricks--return-the-environments-with-then-changed-bricks
  (is (= #{"core" "dev"}
         (env/indirectly-changed-environment-names environments changed-bricks))))