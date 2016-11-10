(ns metrica.etl
  (:require [com.stuartsierra.component :as component]
            [clojure.tools.logging :as log]
            [metrica.graphite :as graphite]
            [clj-jgit.porcelain :refer [load-repo git-log git-branch-list]]
            [clj-jgit.querying :refer [changed-files-with-patch]]
            [clojure.java.io :refer [reader]])
  (:import [java.io StringReader]))

(defn merge? [c]
  (> (count (.getParents c)) 1))

(defn lines-added [diff]
  (let [lines (line-seq (reader (StringReader. diff)))]
    (class lines)
    (-> (filter (partial re-find #"^\+") lines)
        count)))

(defn lines-removed [diff]
  (let [lines (line-seq (reader (StringReader. diff)))]
    (class lines)
    (-> (filter (partial re-find #"^\-") lines)
        count)))

;;metrica/{system-name}/{committer}/lines/(added|removed)/{count}


(defn- ->lines-added [{:keys [author when lines-added]}]
  [(str "metrica.box." author ".lines.added" " " lines-added " " (-> when (.getTime) (/ 1000) (int)))])

(defn- ->lines-removed [{:keys [author when lines-removed]}]
  [(str "metrica.box." author ".lines.removed" " " lines-removed " " (-> when (.getTime) (/ 1000) (int)))])

(defn git->graphite [{:keys [graphite] :as etl} repo hash-a hash-b]
  (->> (git-log repo hash-a hash-b)
       (filter (comp not merge?))
       (map (juxt identity
                  (partial changed-files-with-patch repo)))
       (map (fn [[c diff]]
              {:author (-> c (.getAuthorIdent) (.getEmailAddress))
               :when (-> c (.getAuthorIdent) (.getWhen))
               :lines-added (lines-added diff)
               :lines-removed (lines-removed diff)}))
       (mapcat (fn [m] [(->lines-added m) (->lines-removed m)]))
       (graphite/publish graphite)))


(defrecord Etl [graphite]
  component/Lifecycle

  (start [component]
         (log/info "Starting ETL")
         component)

  (stop [{:keys [conn-manager] :as component}]
        (log/info "Stopping ETL")
        component))

(defn new-etl [options]
  (map->Etl options))



