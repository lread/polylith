(ns ^:no-doc polylith.clj.core.validator.m103-missing-defs
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [polylith.clj.core.validator.shared :as shared]
            [polylith.clj.core.util.interface.color :as color]
            [polylith.clj.core.util.interface :as util]))

(defn ->data-ifc [{:keys [definitions]}]
  (set (filter #(= "data" (:type %)) definitions)))

(defn component-data-defs [interface component]
  (let [data-defs (->data-ifc interface)
        comp-defs (->data-ifc (:interface component))
        missing-defs (set/difference data-defs comp-defs)]
    (when (-> missing-defs empty? not)
      [(str/join ", " (map shared/full-name missing-defs))])))

(defn function-or-macro? [{:keys [type]}]
  (not= "data" type))

(defn functions-and-macros [{:keys [definitions]}]
  (set (filter function-or-macro? definitions)))

(defn component-fn-defs [component interface-functions]
  (let [component-functions-and-macros (-> component :interface functions-and-macros)
        missing-functions-and-macros (set/difference interface-functions component-functions-and-macros)]
    (when (-> missing-functions-and-macros empty? not)
      (vec (sort (map shared/->function-or-macro missing-functions-and-macros))))))

(defn component-error [interface {:keys [name] :as component} interface-functions color-mode]
  (let [component-defs (concat (component-data-defs interface component)
                               (component-fn-defs component interface-functions))]
    (when (-> component-defs empty? not)
      (let [message (str "Missing definitions in "  (color/component name color-mode) "'s interface: "
                         (str/join ", " component-defs))]
        [(util/ordered-map :type "error"
                           :code 103
                           :message (color/clean-colors message)
                           :colorized-message message
                           :components [name])]))))

(defn interface-errors [{:keys [implementing-components] :as interface}
                        name->component color-mode]
  (let [interface-functions (set (mapcat second
                                         (filter #(= 1 (-> % second count) 1)
                                                 (group-by shared/function->id
                                                           (functions-and-macros interface)))))
        ifc-components (map name->component implementing-components)]
    (mapcat #(component-error interface % interface-functions color-mode)
            ifc-components)))

(defn errors [interfaces components color-mode]
  (let [name->component (into {} (map (juxt :name identity) components))]
    (vec (mapcat #(interface-errors % name->component color-mode)
                 interfaces))))
