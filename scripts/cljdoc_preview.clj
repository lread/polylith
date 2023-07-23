#!/usr/bin/env bb

(ns cljdoc-preview
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as process]
            [clojure.java.browse :as browse]
            [clojure.string :as string]
            [lread.status-line :as status]))

;;
;; constants
;;

(def cljdoc-root-temp-dir "/tmp/cljdoc-preview")
(def cljdoc-db-dir (str cljdoc-root-temp-dir  "/db"))
(def cljdoc-container {:name "cljdoc-server"
                       :image "cljdoc/cljdoc"
                       :port 8000})

;;
;; Prerequisites
;;

(defn check-prerequisites []
  (let [missing-cmds (doall (remove fs/which ["git" "docker"]))]
    (when (seq missing-cmds)
      (status/die 1 (string/join "\n" ["Required commands not found:"
                                       (string/join "\n" missing-cmds)])))))
;;
;; os/fs support
;;
(defn cwd[]
  (System/getProperty "user.dir"))

(defn home-dir[]
  (System/getProperty "user.home"))

;;
;; project build info
;;

(defn version []
  (-> (process/shell {:out :string} "clojure -T:build version")
      :out
      string/trim))

(defn local-install []
  (status/line :head "installing libs to local maven repo")
  (process/shell "clojure -T:build install"))

;;
;; git
;;

(defn git-sha []
  (-> (process/shell {:out :string}
                     "git rev-parse HEAD")
      :out
      string/trim))

(defn https-uri
  ;; stolen from cljdoc's http-uri
  "Given a URI pointing to a git remote, normalize that URI to an HTTP one."
  [scm-url]
  (cond
    (.startsWith scm-url "http")
    scm-url

    (or (.startsWith scm-url "git@")
        (.startsWith scm-url "ssh://"))
    (-> scm-url
        (string/replace #":" "/")
        (string/replace #"\.git$" "")
        ;; three slashes because of prior :/ replace
        (string/replace #"^(ssh///)*git@" "https://"))))

(defn git-origin-url-as-https []
  (-> (process/shell {:out :string}
                     "git config --get remote.origin.url")
      :out
      string/trim
      https-uri))

(defn uncommitted-code? []
  (-> (process/shell {:out :string}
                     "git status --porcelain")
      :out
      string/trim
      seq))

(defn unpushed-commits? []
  (let [{:keys [:exit :out]} (process/shell {:continue true :out :string}
                                            "git cherry -v")]
    (if (zero? exit)
      (-> out string/trim seq)
      (status/die 1 "Failed to check for unpushed commits to branch, is your branch pushed?"))))

;;
;; docker
;;

(defn status-server [ container ]
  (let [container-id (-> (process/shell {:out :string}
                                        "docker ps -q -f" (str "name=" (:name container)))
                         :out
                         string/trim)]
    (if (string/blank? container-id) "down" "up")))

(defn docker-pull-latest [ container ]
  (process/shell "docker pull" (:image container)))

(defn stop-server [ container ]
  (when (= "down" (status-server container))
    (status/die 1
                "%s does not appear to be running"
                (:name container)))
  (process/shell "docker" "stop" (:name container) "--time" "0"))

(defn wait-for-server
  "Wait for container's http server to become available, assumes server has valid root page"
  [ container ]
  (status/line :head "Waiting for %s to become available" (:name container))
  (when (= "down" (status-server container))
    (status/die 1
                "%s does not seem to be running.\nDid you run the start command yet?"
                (:name container)))
  (status/line :detail "%s container is running" (:name container))
  (let [url (str "http://localhost:" (:port container))]
    (loop []
      (if-not (try
                (http/get url)
                url
                (catch Exception _e
                  (Thread/sleep 4000)))
        (do (println "waiting on" url " - hit Ctrl-C to give up")
            (recur))
        (println "reached" url)))))

(defn status-server-print [container]
  (status/line :detail (str (:name container) ": " (status-server container))))

;;
;; cljdoc server in docker
;;

(defn cljdoc-ingest [container project version]
  (status/line :head "Ingesting project %s %s\ninto local cljdoc database" project version)
  (process/shell "docker"
                 "run" "--rm"
                 "-v" (str cljdoc-db-dir ":/app/data")
                 "-v" (str (home-dir) "/.m2:/root/.m2")
                 "-v" (str (cwd) ":" (cwd) ":ro")
                 "--entrypoint" "clojure"
                 (:image container)
                 "-M:cli"
                 "ingest"
                  ;; project and version are used to locate the maven artifact (presumably locally)
                 "--project" project "--version" version
                  ;; use git origin to support folks working from forks/PRs
                 "--git" (git-origin-url-as-https)
                  ;; specify revision to allow for previewing when working from branch
                 "--rev" (git-sha)))

(defn start-cljdoc-server [container]
  (when (= "up" (status-server container))
    (status/die 1
                "%s is already running"
                (:name container)))
  (status/line :head "Checking for updates")
  (docker-pull-latest container)
  (status/line :head "Starting %s on port %d" (:name container) (:port container))
  (process/shell "docker"
                 "run" "--rm"
                 "--name" (:name container)
                 "-d"
                 "-p" (str (:port container) ":8000")
                 "-v" (str cljdoc-db-dir ":/app/data")
                 "-v" (str (home-dir) "/.m2:/root/.m2")
                 "-v" (str (cwd) ":" (cwd) ":ro")
                 (:image container)))

(defn view-in-browser [url]
  (status/line :head "opening %s in browser" url)
  (when (not= 200 (:status (http/get url {:throw false})))
    (status/die 1 "Could not reach:\n%s\nDid you run the ingest command yet?" url))
  (browse/browse-url url))


;;
;; main
;;

(defn git-warnings []
  (let [warnings (remove nil?
                         [(when (uncommitted-code?)
                            "There are changes that have not been committed, they will not be previewed")
                          (when (unpushed-commits?)
                            "There are commits that have not been pushed, they will not be previewed")])]
    (when (seq warnings)
      (status/line :warn (string/join "\n" warnings)))))

(defn cleanup-resources []
  (fs/delete-tree cljdoc-db-dir))

(def args-usage "Valid args: (start|ingest|view|stop|status|help)

Commands:
 start                     Start docker containers supporting cljdoc preview
 ingest [clj-poly|clj-api] Locally publishes your lib for cljdoc preview
 view   [clj-poly|clj-api] Opens cljdoc preview in your default browser
 stop                      Stops docker containers supporting cljdoc preview
 status                    Status of docker containers supporting cljdoc preview
 help                      Show this help

Must be run from project root directory.")


(defn short-lib->full-artifact-name [s]
  (str "polylith/clj-" s))

(defn cmd-start [_opts]
  (start-cljdoc-server cljdoc-container))

(defn cmd-ingest [{:keys [opts]}]
  (let [lib (short-lib->full-artifact-name (:lib opts))
        version (version)]
    (git-warnings)
    (local-install)
#_    (cljdoc-ingest cljdoc-container lib version)))

(defn cmd-view [{:keys [opts]}]
  (let [lib (short-lib->full-artifact-name (:lib opts))
        version (version)]
    (wait-for-server cljdoc-container)
    (view-in-browser (str "http://localhost:" (:port cljdoc-container) "/d/" lib "/" version))))

(defn cmd-stop [_opts]
  (stop-server cljdoc-container)
  (cleanup-resources))

(defn cmd-status [_opts]
  (status-server-print cljdoc-container))

(defn cmd-help [_opts]
  (println args-usage))

(defn -main [& args]
  (check-prerequisites)
  (let [valid-projects ["poly" "api"]
        lib-spec {:lib {:default (first valid-projects)
                        :validate {:pred #(some #{%} valid-projects)
                                   :ex-msg (fn [m] (format "Invalid lib: %s\nSpecify one of: %s\nDefault: %s"
                                                           (:value m) (string/join ", " valid-projects) (first valid-projects)))}}}]
    (cli/dispatch
      [{:cmds ["start"] :fn cmd-start}
       {:cmds ["ingest"] :fn cmd-ingest :args->opts [:lib] :spec lib-spec}
       {:cmds ["view"] :fn cmd-view :args->opts [:lib] :spec lib-spec}
       {:cmds ["stop"] :fn cmd-stop}
       {:cmds ["status"] :fn cmd-status}
       {:cmds [] :fn cmd-help}
       ]
      args
      {:error-fn (fn [m]
                  (status/line :error (:msg m)))})))

#_(main/when-invoked-as-script
 (apply -main *command-line-args*))
