(ns sysadmin.store
  "SSoT for the ISCO-08 2522 community systems-administration actor
  (itonami actor pattern, ADR-2607011000 / CLAUDE.md Actors section).
  Modeled on cloud-itonami-isco-4311's bookkeeping.store.

  Domain:

    client   — a registered organization (:client-id, :name)
    system   — a registered managed system {:system-id :client-id :name}
    window   — an APPROVED maintenance window {:window-id :system-id
               :start :end} (integer minutes). Changes may only land
               INSIDE one.
    freeze   — a change freeze {:freeze-id :client-id :start :end
               :reason}. Changes may never OVERLAP one.
    record   — a committed operating record (change draft, applied
               change, credential rotation) — written ONLY via
               commit-record!.
    ledger   — append-only audit trail, commit or hold."
  )

(defprotocol Store
  (client [s client-id])
  (system [s system-id])
  (windows-of [s system-id])
  (freezes-of [s client-id])
  (records-of [s client-id])
  (ledger [s])
  (register-client! [s client])
  (register-system! [s sys])
  (register-window! [s w])
  (register-freeze! [s f])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (client [_ client-id] (get-in @a [:clients client-id]))
  (system [_ system-id] (get-in @a [:systems system-id]))
  (windows-of [_ system-id] (filter #(= system-id (:system-id %)) (:windows @a)))
  (freezes-of [_ client-id] (filter #(= client-id (:client-id %)) (:freezes @a)))
  (records-of [_ client-id] (filter #(= client-id (:client-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-client! [s client]
    (swap! a assoc-in [:clients (:client-id client)] client) s)
  (register-system! [s sys]
    (swap! a assoc-in [:systems (:system-id sys)] sys) s)
  (register-window! [s w]
    (swap! a update :windows (fnil conj []) w) s)
  (register-freeze! [s f]
    (swap! a update :freezes (fnil conj []) f) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:clients {} :systems {} :windows []
                                    :freezes [] :records [] :ledger []}
                                   seed)))))
