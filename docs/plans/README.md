# Plans

This folder holds implementation plans intended to be picked up across multiple
Copilot sessions: one session authors/updates a plan, and later sessions execute
individual phases against it.

## Convention

- One markdown file per initiative, named `kebab-case-topic.md`.
- Each plan is broken into numbered phases, each with a checklist of tasks.
- A session executing a phase should:
  1. Read the plan file first.
  2. Check off completed tasks (`- [ ]` -> `- [x]`) as it goes.
  3. Leave a short status note under the phase if it stops partway through.
  4. Commit the updated plan file alongside the code changes for that phase.
- Do not delete completed plans; keep them as a historical record. Add a
  `Status: Done` marker at the top once all phases are complete.

## Current plans

- [`manifest-fix-plan.md`](./manifest-fix-plan.md) — correctness/security
  hardening of the `.grafico_manifest` export/import path.
- [`upstream-0.9.7-rebase-plan.md`](./upstream-0.9.7-rebase-plan.md) — rebasing
  this fork onto `archimatetool/archi-modelrepository-plugin` 0.9.7.
