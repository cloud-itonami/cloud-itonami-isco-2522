# cloud-itonami-isco-2522

**Community Systems Administration** — the ISCO-08 2522 (Systems
Administrators) actor, an ISCO **Wave 0** occupation per
ADR-2607121000: pure-cognitive work, the LLM-first wave, no robotics
gate.

**Maturity: `:implemented`** — SystemsAdministrationAdvisor ⊣
SystemsAdministrationGovernor as a langgraph StateGraph
(`intake → advise → govern → decide → commit/hold`, human-approval
interrupt), modeled on cloud-itonami-isco-4311's bookkeeping actor.
14 tests / 30 assertions green.

The sysadmin-specific HARD invariants — both interval arithmetic,
neither yields to the advisor's urgency:

1. **Window containment** — an applied change's interval must lie
   INSIDE an approved maintenance window of that system (half-in is
   out; urgency does not create a window).
2. **Freeze integrity** — the interval must not overlap any registered
   change freeze. An emergency that justifies breaking a freeze
   justifies editing the freeze record first, on the record.

Also HARD: invented/foreign systems, invalid intervals, unregistered
organization, non-`:propose` effect. Escalations (always human
sign-off): `:apply-change` (even inside a valid window),
`:rotate-credentials` (sensitive), low confidence (< 0.6).

AGPL-3.0-or-later, forkable by any qualified operator. Part of the
[cloud-itonami](https://itonami.cloud) open business fleet.
