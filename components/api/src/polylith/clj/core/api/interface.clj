(ns polylith.clj.core.api.interface
  (:require [polylith.clj.core.api.core :as core]
            [polylith.clj.core.version.interface :as version])
  (:gen-class))

(def api-version version/api-version)
(def test-runner-api-version version/test-runner-api-version)
(def ws-api-version version/ws-api-version)

(defn projects-to-deploy
  "Returns the projects that have been affected since last deploy,
   tagged in git following the pattern defined by `:release-tag-pattern` in
   `deps.edn`, or `v[0-9]*` if not defined.

   Read more in the documentation on how to use this:
     poly doc page:poly-as-a-library"
  [since]
  (core/projects-to-deploy since))

(defn workspace
  "Returns the workspace or part of the workspace by sending in either
   a key that can be found in the `:tag-patterns` keys in `workspace.edn`,
   optionally prefixed with 'previous-', or a git SHA, as the first argument,
   and a list of keywords, strings, or numbers as the second argument.
   `:keys` and `:count` are also valid keys to send in.

   Read more in the documentation on how to use this:
     poly doc page:poly-as-a-library"
  [stable-point & keys]
  (core/workspace stable-point keys))

(comment
  (projects-to-deploy nil)
  (projects-to-deploy "release")
  (workspace nil)
  (workspace "release")
  (workspace "stable" "settings" "interface-ns")
  #__)
