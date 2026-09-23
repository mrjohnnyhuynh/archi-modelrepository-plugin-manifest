# Upstream 0.9.7 Rebase Plan

Status: In progress — rebased locally onto upstream 0.9.7; build and smoke
validation remain for the maintainer.

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

- [x] Rebase directly on the maintainer's local `master`; no dedicated branch
      or PR is required for this single-contributor fork.
- [x] Confirm `upstream` remote exists and is reachable:
      `git remote -v` should list both `origin` and `upstream`.
- [x] Fetch upstream refs/tags: `git fetch upstream --tags`.

Status: Completed locally. The repository was rebased in place with
`git rebase --autostash 0.9.7`.

### Phase 2 — Assess divergence

- [x] Identify the `0.9.7` tag/ref on `upstream`.
- [x] Diff/log the fork's history against `upstream/master` and the `0.9.7`
      tag to understand what changed upstream since the fork point:
      `git log --oneline --decorate --graph 0.9.7..HEAD` and the reverse
      direction. (`0.9.7` is a fetched tag; it is not normally an
      `upstream/0.9.7` remote-tracking branch.)
- [x] List files touched on both sides to anticipate conflict-prone areas,
      especially:
      - `grafico/ArchiRepository.java`
      - `grafico/GraficoModelExporter.java`
      - `grafico/GraficoModelImporter.java`
      - `grafico/GraficoModelLoader.java`
      - `grafico/GraficoManifest.java` (fork-only file; should not conflict
        directly, but exporter/repository integration points will)
      - `authentication/*`
      - any UI/view changes touched by upstream 0.9.x releases
- [x] Inventory and reconcile release metadata against upstream `0.9.7`,
      including:
      - `org.archicontribs.modelrepository/META-INF/MANIFEST.MF`
        (formerly `Bundle-Version: 0.9.6.manifest-qualifier`)
      - `org.archicontribs.modelrepository.commandline/META-INF/MANIFEST.MF`
        (`Bundle-Version` and the required core bundle version)
      - `org.archicontribs.modelrepository.tests/META-INF/MANIFEST.MF`
      - `org.archicontribs.modelrepository.feature/feature.xml`
        (`feature version`)
      - any release/version references in `README.md`, build properties, or
        update-site metadata if added later
      Use one consistent fork versioning scheme: the upstream base should be
      `0.9.7`, while any fork-specific qualifier (such as `manifest`) must be
      intentional, documented, and applied consistently rather than leaving
      stale `0.9.6` references. The local fork now uses
      `0.9.7.manifest-qualifier` consistently for its bundles and feature.

Status: Completed locally. Conflicts were reviewed in the core bundle
manifest, `ArchiRepository.java`, and the upstream importer path.

### Phase 3 — Rebase

- [x] Rebase the fork branch onto the fetched `0.9.7` tag:
      `git rebase 0.9.7` (or use an explicit `--onto` range if the fork's
      merge history requires it).
- [x] Resolve conflicts file by file, keeping upstream's non-manifest logic
      and reapplying the fork's manifest-specific changes on top.
- [x] Re-apply/verify fork-specific pieces are intact after conflict
      resolution:
      - manifest read/write integration in exporter
      - selective `git add`/`git rm` staging
      - selective EMF reload (if merged)
- [x] Update and verify all bundle/feature version declarations as one
      metadata change; do not leave core, command-line, tests, and feature
      metadata on mixed 0.9.6/0.9.7 bases.

### Phase 4 — Validate

- [ ] Build in a full Archi Eclipse PDE workspace (not available in this
      session's container).
- [ ] Re-run the manifest-fix-plan regression scenarios to confirm nothing
      upstream changed the assumptions those fixes depend on (e.g. Git
      command usage, resource save APIs, folder layout constants).
- [x] Verify metadata consistency: no unintended `0.9.6` release references
      remain, the command-line bundle resolves the rebased core bundle, and
      the feature version matches the selected fork versioning scheme.
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
