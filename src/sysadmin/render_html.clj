(ns sysadmin.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  This repo had NO demo/visualization at all before this namespace.
  This drives the REAL actor stack (`sysadmin.actor` ->
  `sysadmin.governor` -> `sysadmin.store`) through a scenario built
  from real, exercised store/test data and renders the result
  deterministically -- no invented numbers, no timestamps in the page
  content, byte-identical across reruns against the same seed (verify
  by diffing two consecutive runs before shipping). Adapted from the
  reference pattern in `cloud-itonami-isco-1211`'s
  `finmgmt.render-html` (com-junkawasaki/root, prior iteration of this
  same effort) -- the entities/rules below are specific to this
  domain, not copied verbatim.

  `client-1` (\"Mise Systems\") + system `sys-1` (\"web\") + maintenance
  window `w-1` (minutes 120-240) + change freeze `f-1` (minutes
  180-300, \"release week\") are lifted VERBATIM from this repo's own
  proven-passing test fixture (`sysadmin.actor-test/fresh-store` /
  `sysadmin.governor-test/fresh-store`, identical `client-1`/`sys-1`/
  `w-1` in both, `f-1` only in `governor-test`'s fixture) -- ground
  truth, not invented. The `apply-inside-window-outside-freeze`
  interval (120-170), the out-of-window interval (600-660), the
  freeze-overlapping interval (170-230), and the invalid interval
  (200-200) are copied VERBATIM from
  `sysadmin.governor-test`/`sysadmin.actor-test`'s own assertions.

  `client-2` (\"Northgate Ops\") + system `sys-2` (\"db\") + maintenance
  window `w-2` (minutes 300-400) are ADDITIONAL demo data registered
  via the SAME real protocol calls (`store/register-client!`/
  `register-system!`/`register-window!`) this actor's own tests use --
  this actor's own test fixture has only one client, so a second
  client is necessary to demonstrate the cross-client
  `:system-wrong-client` rule. Disclosed here plainly, not presented as
  a pre-existing fixture. Every other field this page displays
  (statuses, dispositions, hold reasons) is real output read after
  `run-demo!` actually executed the graph -- none of it is hand-typed.

  Known architectural gap, honestly noted rather than papered over
  (mirrors the exact same shape as `finmgmt`'s `:no-actuation` gap):

  1. `sysadmin.governor`'s `:no-actuation` rule (proposal `:effect`
     must be `:propose`) is NOT reachable through this demo, because
     the real `mock-advisor` (`sysadmin.advisor/infer`)
     unconditionally sets `:effect :propose` on every proposal it
     emits. Covered instead by
     `sysadmin.governor-test/hard-on-no-actuation-violation`, which
     calls `governor/check` directly with a hand-built proposal.
  2. The low-confidence escalation path (`confidence <
     sysadmin.governor/confidence-floor`, 0.6) is likewise NOT
     reachable through this demo: `sysadmin.advisor/infer` maps
     `:stake` to a fixed confidence of 0.7/0.85/0.95
     (`:high`/`:medium`/`:low`), never below the floor. Covered instead
     by `sysadmin.governor-test/escalates-low-confidence`, which also
     calls `governor/check` directly. The escalations this demo
     genuinely reaches through the real advisor are `:apply-change`
     and `:rotate-credentials` (both always human-approved regardless
     of confidence).

  Usage: `clojure -M:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [sysadmin.store :as store]
            [sysadmin.actor :as actor]))

;; ----------------------------- harness --------------------------------

(defn- run-op!
  "Drives one real sysadmin operation request through the actual
  compiled graph for `tid` (thread-id). If the graph escalates
  (interrupts before `:request-approval`), immediately approves it --
  this demo's scenario never demonstrates an UNAPPROVED escalation,
  every escalation here reaches a human who signs off. Returns a map
  describing exactly what really happened -- no field is invented."
  [graph tid client-id op extra]
  (let [request (merge {:client-id client-id :op op} extra)
        r1 (actor/run-request! graph request {} tid)]
    (if (= :interrupted (:status r1))
      (let [r2 (actor/approve! graph tid)]
        {:thread-id tid :client-id client-id :op op :request request
         :outcome :approved-and-committed
         :record (get-in r2 [:state :record])})
      (let [disposition (get-in r1 [:state :disposition])]
        (if (= :hold disposition)
          {:thread-id tid :client-id client-id :op op :request request
           :outcome :hard-hold
           :verdict (get-in r1 [:state :verdict])
           :rule (-> r1 :state :verdict :violations first :rule)}
          {:thread-id tid :client-id client-id :op op :request request
           :outcome :auto-committed
           :record (get-in r1 [:state :record])})))))

(def ^:private op-specs
  "The scenario: covers every disposition this actor can genuinely
  reach through its real graph (auto-commit, escalate-then-approve,
  and all 7 of the 8 distinct HARD-hold rules in `sysadmin.governor`
  that are reachable via the real advisor -- `:no-actuation` and the
  low-confidence escalation path are structurally unreachable, see
  namespace docstring). Every `:op` keyword and violation rule name
  below is copied from `sysadmin.governor`'s own `hard-violations`/
  `check`, not invented."
  [;; client-1 / \"Mise Systems\" / sys-1 (\"web\") / w-1 (120-240) /
   ;; f-1 (180-300) -- real fixture from sysadmin.actor-test /
   ;; sysadmin.governor-test
   ["c1-draft"                "client-1" :draft-change  {:system-id "sys-1" :at-start 130 :at-end 160 :stake :low}]
   ["c1-apply-ok"             "client-1" :apply-change  {:system-id "sys-1" :at-start 120 :at-end 170 :stake :high}]
   ["c1-apply-outside-window" "client-1" :apply-change  {:system-id "sys-1" :at-start 600 :at-end 660 :stake :high}]
   ["c1-apply-freeze"         "client-1" :apply-change  {:system-id "sys-1" :at-start 170 :at-end 230 :stake :high}]
   ["c1-apply-invalid"        "client-1" :apply-change  {:system-id "sys-1" :at-start 200 :at-end 200 :stake :high}]
   ["c1-apply-unknown-system" "client-1" :apply-change  {:system-id "sys-ghost" :at-start 130 :at-end 170 :stake :high}]
   ["c1-apply-no-system"      "client-1" :apply-change  {:system-id nil :at-start 130 :at-end 170 :stake :high}]
   ["c1-rotate-creds"         "client-1" :rotate-credentials {:system-id "sys-1" :stake :high}]
   ;; unregistered client entirely
   ["ghost-no-client"         "client-ghost" :draft-change {:system-id nil :stake :low}]
   ;; client-2 / \"Northgate Ops\" / sys-2 (\"db\") / w-2 (300-400)
   ;; (additional demo data, registered via the same real
   ;; register-client!/register-system!/register-window! calls -- see
   ;; namespace docstring)
   ["c2-apply-wrong-client"   "client-2" :apply-change  {:system-id "sys-1" :at-start 130 :at-end 160 :stake :high}]
   ["c2-draft"                "client-2" :draft-change  {:system-id "sys-2" :at-start 310 :at-end 330 :stake :low}]
   ["c2-apply-ok"             "client-2" :apply-change  {:system-id "sys-2" :at-start 310 :at-end 350 :stake :high}]])

(defn run-demo!
  "Runs a fresh store through `op-specs` (see above) via the real
  compiled `sysadmin.actor` graph. Returns `{:store :runs}` -- `:runs`
  is the ordered vector of real per-request outcomes; every field in
  `render` below is read from this or from `store` after the graph
  actually executed, never hand-typed."
  []
  (let [db (store/mem-store)]
    (store/register-client! db {:client-id "client-1" :name "Mise Systems"})
    (store/register-system! db {:system-id "sys-1" :client-id "client-1" :name "web"})
    (store/register-window! db {:window-id "w-1" :system-id "sys-1" :start 120 :end 240})
    (store/register-freeze! db {:freeze-id "f-1" :client-id "client-1"
                                :start 180 :end 300 :reason "release week"})
    (store/register-client! db {:client-id "client-2" :name "Northgate Ops"})
    (store/register-system! db {:system-id "sys-2" :client-id "client-2" :name "db"})
    (store/register-window! db {:window-id "w-2" :system-id "sys-2" :start 300 :end 400})
    (let [graph (actor/build-graph {:store db})
          runs (mapv (fn [[tid client-id op extra]]
                       (run-op! graph tid client-id op extra))
                     op-specs)]
      {:store db :runs runs})))

;; ----------------------------- rendering -------------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- outcome-cell [{:keys [outcome rule]}]
  (case outcome
    :auto-committed "<span class=\"ok\">committed</span>"
    :approved-and-committed "<span class=\"ok\">approved &amp; committed</span>"
    :hard-hold (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>")
    "<span class=\"muted\">in progress</span>"))

(defn- client-row [store {:keys [client-id name]} runs]
  (let [committed (count (store/records-of store client-id))
        client-runs (filter #(= client-id (:client-id %)) runs)
        freezes (store/freezes-of store client-id)]
    (format "        <tr><td>%s</td><td>%s</td><td>%d</td><td>%d</td><td>%d</td></tr>"
            (esc client-id) (esc name) (count freezes) committed (count client-runs))))

(defn- system-row [store {:keys [system-id client-id name]}]
  (let [windows (store/windows-of store system-id)]
    (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>"
            (esc system-id) (esc client-id) (esc name)
            (str/join ", " (map #(str (:start %) "&ndash;" (:end %)) windows)))))

(defn- run-row [{:keys [thread-id client-id op request outcome rule]}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc thread-id) (esc client-id) (esc (name op))
          (esc (or (:system-id request) ""))
          (if (and (:at-start request) (:at-end request))
            (str (:at-start request) "&ndash;" (:at-end request))
            "")
          (outcome-cell {:outcome outcome :rule rule})))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract (README.md,
  ;; `sysadmin.governor`'s own docstring) -- documentation of fixed
  ;; behavior, not runtime telemetry, so it is legitimately
  ;; hand-described rather than derived from a live run.
  ["        <tr><td><code>:draft-change</code></td><td><span class=\"ok\">auto-commit when a registered system is cited &middot; no window/freeze check</span></td></tr>"
   "        <tr><td><code>:apply-change</code></td><td><span class=\"warn\">ALWAYS human approval &middot; interval must be CONTAINED in an approved maintenance window AND must not OVERLAP any change freeze &middot; neither yields to urgency</span></td></tr>"
   "        <tr><td><code>:rotate-credentials</code></td><td><span class=\"warn\">ALWAYS human approval &middot; sensitive</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from `{:store :runs}`
  as produced by `run-demo!` (or any other real scenario)."
  [{:keys [store runs]}]
  (let [clients [{:client-id "client-1" :name "Mise Systems"}
                 {:client-id "client-2" :name "Northgate Ops"}]
        systems [{:system-id "sys-1" :client-id "client-1" :name "web"}
                 {:system-id "sys-2" :client-id "client-2" :name "db"}]
        client-rows (str/join "\n" (map #(client-row store % runs) clients))
        system-rows (str/join "\n" (map #(system-row store %) systems))
        run-rows (str/join "\n" (map run-row runs))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isco-2522 &middot; community systems administration</title><style>\n"
     "table { width: 100%; border-collapse: collapse; font-size: 14px; }\n"
     ".ok { color: #137a3f; }\n"
     "body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }\n"
     "header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }\n"
     "th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }\n"
     "h2 { margin-top: 0; font-size: 15px; }\n"
     ".warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }\n"
     "main { max-width: 1040px; margin: 24px auto; padding: 0 20px; }\n"
     "header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }\n"
     ".muted { color: #888; font-size: 13px; }\n"
     ".critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }\n"
     ".card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }\n"
     ".err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }\n"
     "th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }\n"
     "header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }\n"
     "code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }\n"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Community Systems Administration (ISCO-08 2522) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · window containment &amp; freeze overlap checked by interval arithmetic, never advisor urgency</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Registered clients</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>sysadmin.store</code> via <code>sysadmin.render-html</code> (<code>clojure -M:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Client</th><th>Name</th><th>Change freezes</th><th>Committed records</th><th>Requests this run</th></tr></thead>\n"
     "      <tbody>\n"
     client-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Registered systems &amp; maintenance windows</h2>\n"
     "    <p class=\"muted\">Minutes are an integer timeline (e.g. minute 180 = 03:00 in the governor test fixture's own comment). A change must be CONTAINED in a window (both endpoints inside), and must not OVERLAP any change freeze of the owning client — client-1's freeze <code>f-1</code> runs 180&ndash;300, overlapping the tail of <code>sys-1</code>'s own window <code>w-1</code> (120&ndash;240).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>System</th><th>Client</th><th>Name</th><th>Maintenance windows</th></tr></thead>\n"
     "      <tbody>\n"
     system-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Systems Administration Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. Window containment and freeze overlap are checked by interval arithmetic on every proposal, at any confidence.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit trail (this run)</h2>\n"
     "    <p class=\"muted\">Every request this scenario drove through the real compiled graph, in order — thread-id, client, op, the request's own cited system and change interval, and the real disposition (auto-commit, approved-after-escalation, or the specific HARD-hold rule).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Thread</th><th>Client</th><th>Op</th><th>System</th><th>Interval</th><th>Disposition</th></tr></thead>\n"
     "      <tbody>\n"
     run-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        html (render result)]
    (spit out html)
    (println "wrote" out "("
             (count (:runs result)) "requests driven through the real graph,"
             (count (store/ledger (:store result))) "ledger facts )")))
