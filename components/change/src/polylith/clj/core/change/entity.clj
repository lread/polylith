(ns ^:no-doc polylith.clj.core.change.entity
  (:require [polylith.clj.core.path-finder.interface.criterias :as c]
            [polylith.clj.core.path-finder.interface.select :as select]
            [polylith.clj.core.path-finder.interface.extract :as extract]))

(defn changed-entities
  "Returns the bricks and projects that has changed based on a list of files"
  [paths disk-paths]
  (let [path-entries (extract/from-paths {:src paths} disk-paths)]
    {:changed-bases (select/names path-entries c/base? c/exists?)
     :changed-components (select/names path-entries c/component? c/exists?)
     :changed-projects (select/names path-entries c/project? c/exists?)}))
