(ns sysadmin.governor
  "SystemsAdministrationGovernor — the independent safety/traceability
  layer for the ISCO-08 2522 community systems-administration actor
  (itonami actor pattern, ADR-2607011000 / CLAUDE.md Actors section).
  Modeled on cloud-itonami-isco-4311's bookkeeping.governor. SysAdmin-
  specific twist: change timing is checked DETERMINISTICALLY twice —
  the change interval must be CONTAINED in an approved maintenance
  window, and must not OVERLAP any change freeze. Both are interval
  arithmetic; neither yields to the advisor's urgency.

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. client provenance — the organization must be registered.
    2. no-actuation      — proposal :effect must be :propose.
    3. system basis      — the cited system must be REGISTERED and
                           belong to this client (no invented systems).
    4. window containment — an :apply-change's [:at-start :at-end] must
                           lie INSIDE a registered maintenance window
                           of that system.
    5. freeze integrity  — the interval must not overlap ANY registered
                           change freeze of this client. An emergency
                           that justifies breaking a freeze justifies
                           editing the freeze record first, on the
                           record.
  ESCALATION invariants (:escalate? true, human sign-off):
    6. :op :apply-change (real system mutation — always human).
    7. :op :rotate-credentials (sensitive — always human).
    8. low confidence (< `confidence-floor`)."
  (:require [sysadmin.store :as store]))

(def confidence-floor 0.6)

(defn- contained? [{s :at-start e :at-end} {ws :start we :end}]
  (and (>= s ws) (<= e we)))

(defn- overlaps? [{s :at-start e :at-end} {fs :start fe :end}]
  (< (max s fs) (min e fe)))

(defn- hard-violations [{:keys [request proposal]} client-record sys store]
  (let [{:keys [op system-id at-start at-end]} proposal
        sys-op? (contains? #{:draft-change :apply-change :rotate-credentials} op)
        apply? (= :apply-change op)
        valid-times? (and (integer? at-start) (integer? at-end) (< at-start at-end))
        interval {:at-start at-start :at-end at-end}]
    (cond-> []
      (nil? client-record)
      (conj {:rule :no-client :detail "未登録 client"})

      (not= :propose (:effect proposal))
      (conj {:rule :no-actuation :detail "effect は :propose のみ許可（直接書込禁止）"})

      (and sys-op? (nil? system-id))
      (conj {:rule :no-system :detail "対象 system の引用が必須"})

      (and sys-op? system-id (nil? sys))
      (conj {:rule :unknown-system :detail (str "未登録 system: " system-id)})

      (and sys-op? sys (not= (:client-id sys) (:client-id request)))
      (conj {:rule :system-wrong-client :detail "system が別 client のもの"})

      (and apply? (not valid-times?))
      (conj {:rule :invalid-interval
             :detail (str "変更区間が不正: " at-start " - " at-end)})

      (and apply? sys valid-times?
           (not-any? #(contained? interval %) (store/windows-of store system-id)))
      (conj {:rule :outside-window
             :detail "承認済みメンテナンス窓の区間内に収まっていない（窓の外の変更は承認不可 — 窓を取ること）"})

      (and apply? valid-times?
           (some #(overlaps? interval %) (store/freezes-of store (:client-id request))))
      (conj {:rule :freeze-violation
             :detail "変更凍結期間と重複（凍結を破る緊急性は、まず凍結記録の変更を記録に残して行うこと）"}))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `sysadmin.store/Store`. Pure — never mutates
  the store."
  [request context proposal store]
  (let [client-record (store/client store (:client-id request))
        sys (some->> (:system-id proposal) (store/system store))
        hard (hard-violations {:request request :proposal proposal}
                              client-record sys store)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        risky-op? (contains? #{:apply-change :rotate-credentials} (:op proposal))]
    {:ok? (and (not hard?) (not low?) (not risky-op?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? risky-op?))}))
