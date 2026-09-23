# Upstream 0.9.7 Rebase Plan

Status: Not started

## Goal

Integrate upstream `archimatetool/archi-modelrepository-plugin` changes up to
the `0.9.7` release while preserving this fork's manifest-optimization work
(see [`manifest-fix-plan.md`](./manifest-fix-plan.md)).

## Remotes

- `origin` = `https://github.com/mrjohnnyhuynh/archi-modelrepository-plugin-manifest.git`
  (this fork; push target)
- `upstream` = `https://github.com/archimatetool/archi-modelrepository-plugin.git`
  (read-only reference; do not push here)

## Phases

### Phase 1 — Setup

- [ ] Create a dedicated branch for the rebase work, off the current
      fork branch (do not rebase `master` directly until validated).
- [ ] Confirm `upstream` remote exists and is reachable:
      `git remote -v` should list both `origin` and `upstream`.
- [ ] Fetch upstream refs/tags: `git fetch upstream --tags`.

### Phase 2 — Assess divergence

- [ ] Identify the `0.9.7` tag/ref on `upstream`.
- [ ] Diff/log the fork's history against `upstream/master` and the `0.9.7`
      tag to understand what changed upstream since the fork point:
      `git log --oneline --decorate --graph upstream/0.9.7..HEAD` and the
      reverse direction.
- [ ] List files touched on both sides to anticipate conflict-prone areas,
      especially:
      - `grafico/ArchiRepository.java`
      - `grafico/GraficoModelExporter.java`
      - `grafico/GraficoManifest.java` (fork-only file; should not conflict
        directly, but exporter/repository integration points will)
      - `authentication/*`
      - any UI/view changes touched by upstream 0.9.x releases

### Phase 3 — Rebase

- [ ] Rebase the fork branch onto `upstream/0.9.7` in small, reviewable
      commits: `git rebase upstream/0.9.7` (or interactive rebase to squash
      /reorder fork-specific commits first if history is messy).
- [ ] Resolve conflicts file by file, keeping upstream's non-manifest logic
      and reapplying the fork's manifest-specific changes on top.
- [ ] Re-apply/verify fork-specific pieces are intact after conflict
      resolution:
      - manifest read/write integration in exporter
      - selective `git add`/`git rm` staging
      - selective EMF reload (if merged)

### Phase 4 — Validate

- [ ] Build in a full Archi Eclipse PDE workspace (not available in this
      session's container).
- [ ] Re-run the manifest-fix-plan regression scenarios to confirm nothing
      upstream changed the assumptions those fixes depend on (e.g. Git
      command usage, resource save APIs, folder layout constants).
- [ ] Smoke-test basic coArchi flows: clone, commit, refresh, publish,
      branch switch, merge, conflict resolution.

### Phase 5 — Land

- [ ] Open a PR from the rebase branch into `origin/master` (not directly
      onto `master`) so the rebase and any conflict-resolution decisions are
      reviewable.
- [ ] Keep the pre-rebase branch/tag around until the rebase is validated,
      as a rollback point.
- [ ] Once merged, tag or note the fork's new base point
      (upstream 0.9.7) for future rebases.

## Risks / notes

- Rebasing rewrites history on the fork branch; anyone with a local clone of
  that branch will need to reset to the new history after this lands.
- If upstream has changed the manifest-adjacent methods significantly
  (`exportModel`, `commitChanges`, `pullFromRemote`, etc.), the manifest-fix
  plan phases may need to be redone or re-verified against the new upstream
  code rather than assumed still valid.
- Prefer doing this rebase *after* Phase 1–2 of the manifest-fix-plan land,
  so the riskiest correctness fixes are validated against the current
  codebase first, and the rebase conflict surface is smaller and better
  understood.
