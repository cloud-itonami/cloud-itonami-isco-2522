(ns sysadmin.advisor
  "SystemsAdministrationAdvisor — proposes a sysadmin operation (draft a
  change, apply a change, rotate credentials) for a registered
  organization. Swappable mock/llm; the advisor ONLY proposes —
  `sysadmin.governor` checks window containment and freeze overlap
  independently. Modeled on cloud-itonami-isco-4311's advisor.

  A proposal: {:op :draft-change|:apply-change|:rotate-credentials
               :effect :propose :system-id str :at-start int :at-end int
               :stake kw :confidence n :rationale str}")

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map"))

(defn- infer [_store {:keys [op stake system-id at-start at-end] :as request}]
  {:op op
   :effect :propose
   :system-id system-id
   :at-start at-start
   :at-end at-end
   :stake (or stake :low)
   :confidence (case (or stake :low) :high 0.7 :medium 0.85 :low 0.95)
   :rationale (str "proposed " (name op) " for client " (:client-id request))})

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request] (infer store request))))

(def ^:private system-prompt
  "You are a systems administration advisor. Given a request, propose
   an :op, the :system-id, the change interval :at-start/:at-end
   (integer minutes), an honest :confidence and a :stake. Never claim a
   window or ignore a freeze — the governor checks both intervals.")

(defn- parse-proposal [content]
  (try
    (let [p (read-string content)]
      (if (map? p)
        (assoc p :effect :propose)
        {:op :unknown :effect :propose :confidence 0.0 :stake :high
         :rationale "unparseable LLM response"}))
    (catch #?(:clj Exception :cljs js/Error) _
      {:op :unknown :effect :propose :confidence 0.0 :stake :high
       :rationale "LLM response parse failure"})))

(defn llm-advisor
  [chat-model model-generate-fn gen-opts]
  (reify Advisor
    (-advise [_ _store request]
      (let [msgs [{:role :system :content system-prompt}
                  {:role :user :content (str "operation request: " (pr-str request))}]
            resp (model-generate-fn chat-model msgs gen-opts)]
        (parse-proposal (:content resp))))))
