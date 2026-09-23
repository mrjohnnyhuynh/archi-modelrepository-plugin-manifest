# Manifest Fix Plan — `archi-modelrepository-plugin-manifest`

Status: Not started

## Background

This fork of coArchi adds a `.grafico_manifest` MD5-hash cache so
commit/refresh/publish can re-export and re-hash only Grafico model XML files
that actually changed, instead of deleting and rebuilding the full export on
every run. That optimization matters for very large models (~100K elements),
but the manifest code path has several correctness and security issues in
`org.archicontribs.modelrepository/src/org/archicontribs/modelrepository/grafico/`
and adjacent repository/authentication code.

No build environment was available when this plan was written; implementation
and regression testing must happen in a full Archi Eclipse PDE workspace.

## Findings summary

| # | Severity | File | Issue |
|---|---|---|---|
| 1 | Critical | ArchiRepository.java (`exportModelToGraficoFiles`) | Index stat normalization can mask real edits without verifying working-tree bytes match the indexed blob |
| 2 | Critical | GraficoModelExporter.java (`exportModel`) | Export is not transactional: failures are surfaced after deletion/manifest save |
| 3 | Critical | GraficoModelExporter.java | `writtenFiles` (`HashSet`) and exception capture are not thread-safe across `JobGroup` workers |
| 4 | High | ArchiRepository.java (`cloneModel`/`pullFromRemote`) | Manifest rebuilt from Git blob bytes, not checked-out working-tree bytes (EOL/filters risk) |
| 5 | Critical | ArchiRepository.java, GraficoManifest.java | Manifest cache lives inside the tracked worktree (`model/.grafico_manifest`); can be committed, cleaned, or become attacker-controlled |
| A | Critical | GraficoManifest.java, GraficoModelExporter.java | Manifest keys are trusted without validation; malformed/traversal keys can delete arbitrary files |
| B | Medium | ArchiRepository.java (`pullFromRemote`) | Pull records all changed files under `model/`, not just `.xml` |
| C | Critical | ArchiRepository.java (`checkDeleteLockFile`) | `index.lock` is deleted unconditionally, risking concurrent index corruption |
| D | Critical | GraficoModelExporter.java (`saveImages`) | Image export paths are not validated against the images root (path traversal) |
| E | Medium | GraficoManifest.java (`save`, `buildFromDisk`) | Manifest save is non-atomic; `Files.walk` not in try-with-resources |
| F | Medium | authentication/SSHCredentialsProvider.java | Auto-answers `true` to all SSH `YesNoType` prompts (host-key MITM risk) |
| 6 | Medium | GraficoModelExporter.java (`exportModel`) | Changed resources are serialized twice (once for hashing, once to write) |
| 7 | Medium | GraficoModelExporter.java (`saveImages`) | Images are always fully rebuilt; not covered by manifest optimization |
| 8 | Low | GraficoUtils.java (`getUniqueLocalFolder`) | `file.list().length` can NPE if `list()` returns null |
| 9 | Low | ArchiRepository.java (`getWorkingTreeFileContents`) | Unused `Git` instance created |
| 10 | Low | grafico/* | Pervasive ungated `System.err.println` debug logging |

## Phases

### Phase 1 — Eliminate data-loss and concurrency hazards first

- [ ] Remove or harden the `DirCacheEntry` index-stat normalization in
      `ArchiRepository.exportModelToGraficoFiles` (Finding 1). Prefer deleting
      it; if retained, only refresh metadata after a byte-for-byte compare of
      working-tree file vs. indexed blob.
- [ ] Make `GraficoModelExporter.exportModel` transactional (Finding 2):
      collect all worker failures first; abort before the deletion loop and
      before `manifest.save()` if any write failed.
- [ ] Replace `writtenFiles` (`HashSet<Path>`) with a thread-safe set
      (`ConcurrentHashMap.newKeySet()`), and replace `ExceptionProgressMonitor.ex`
      with an atomic/synchronized failure holder (Finding 3).
- [ ] Make `GraficoManifest.save()` atomic: write to a temp file in the same
      directory, then atomic move into place (Finding E, part 1).

### Phase 2 — Block unsafe file deletion / path traversal

- [ ] Add a single manifest-key validation/canonicalization helper: reject
      absolute paths, `.`/`..` segments, backslashes, non-`.xml` entries;
      require the resolved path to remain under the model root (Finding A).
      Apply it in `GraficoManifest.load` and before the deletion loop in
      `GraficoModelExporter`.
- [ ] Constrain `saveImages()` image paths to remain inside `<repo>/images`
      after normalization; reject absolute/traversal/symlink-escape paths
      (Finding D).
- [ ] Stop auto-deleting `index.lock` in `checkDeleteLockFile()`; surface lock
      contention as a user-visible error instead (Finding C).

### Phase 3 — Relocate the manifest cache out of the tracked worktree

- [ ] Change `GraficoManifest` to accept an explicit manifest file `Path`
      instead of deriving it from the model folder.
- [ ] Store the manifest under Git metadata (e.g. via
      `Repository.getDirectory()`), not `<worktree>/model/.grafico_manifest`
      (Finding 5).
- [ ] Add `model/.grafico_manifest` to `.gitignore` as a defensive fallback.
- [ ] Write a migration note/step: `git rm --cached model/.grafico_manifest`
      for already-tracked legacy files, then delete the obsolete worktree
      cache file.
- [ ] Re-verify `resetToRef()` behavior once the manifest lives outside the
      worktree (it should survive `git clean`/hard reset).

### Phase 4 — Fix clone/pull manifest rebuild inputs

- [ ] Filter pull-driven manifest updates to `.xml` under `model/` only,
      matching clone behavior (Finding B).
- [ ] Rebuild the manifest from actual working-tree bytes after
      checkout/merge in `cloneModel`/`pullFromRemote`, instead of hashing Git
      blob bytes directly (Finding 4). Reuse/fix `GraficoManifest.buildFromDisk()`
      (wrap `Files.walk` in try-with-resources, propagate errors) (Finding E,
      part 2).
- [ ] Re-check the interaction with Phase 1's index-normalization fix now that
      manifest generation is disk-based.

### Phase 5 — Performance, cleanup, and lower-severity hardening

- [ ] Reuse serialized bytes from the hashing pass when writing changed
      resources, avoiding double EMF serialization (Finding 6).
- [ ] Decide and document the scope of image export optimization: either
      extend manifest hashing to images, or explicitly document that only
      model XML is optimized (Finding 7).
- [ ] Null-check `file.list()` in `GraficoUtils.getUniqueLocalFolder` (Finding 8).
- [ ] Remove the unused `Git` instance in
      `ArchiRepository.getWorkingTreeFileContents` (Finding 9).
- [ ] Gate `System.err.println` debug logging behind a flag or a proper
      plugin logging facility (Finding 10).
- [ ] Harden SSH host-key handling in `SSHCredentialsProvider` (Finding F):
      replace blanket "yes" answers with explicit verification/user
      confirmation.

## Testing & validation

No build/test environment was available when this plan was written (requires
a full Archi Eclipse PDE workspace with `com.archimatetool.editor` and Eclipse
RCP bundles; Tycho/Maven build files were previously removed). Validate in a
local Archi PDE dev environment.

Regression scenarios to cover explicitly:

1. Failed parallel writes: simulate one worker failing during
   `GraficoModelExporter.exportModel`; verify no deletion runs, no new
   manifest replaces the old one, and the failure surfaces immediately.
2. Concurrent export tracking: exercise multiple changed resources in
   parallel; verify every successfully written file appears in
   `writtenFiles` and is staged/committed.
3. Malformed manifest handling: feed `..`, absolute paths, backslashes,
   non-XML names, symlink-escape entries; verify rejection and no deletion
   outside the model root.
4. Legacy committed-manifest migration: start from a repo that already
   tracks `model/.grafico_manifest`; verify migration to Git-metadata
   storage and `.gitignore` safety net.
5. Reset/clean behavior: exercise Abort Changes / Reset to Remote / Undo
   Last Commit / Restore Commit / Switch Branch; verify the relocated
   manifest survives and is not wiped by `git clean`.
6. Attributes/EOL handling: verify clone/pull manifest hashes match
   checked-out working-tree bytes, not raw blob bytes, under
   `.gitattributes`/EOL normalization.
7. Non-XML files under `model/`: verify pull does not record them in the
   manifest, and export does not delete them as stale Grafico outputs.
8. Image path safety: feed traversal attempts and valid in-root paths;
   verify only descendants of the images root are written.
9. Lock-file behavior: reproduce `index.lock` contention; verify the code
   reports the problem rather than deleting an active lock.

Manual validation checklist:

- [ ] Commit/refresh/publish still skip unchanged XML resources.
- [ ] Real XML edits are always detected and staged.
- [ ] Partial export failures do not update the manifest.
- [ ] Reset/clean/branch-switch flows do not destroy the cache or commit it
      into history.
- [ ] Clone/pull manifest rebuilds match on-disk checkout state.

## Process notes

- Work in the maintainer fork: `origin = github.com/mrjohnnyhuynh/archi-modelrepository-plugin-manifest`.
- Use a feature branch and PR back to `origin/master` rather than pushing
  directly, since the manifest-path relocation and deletion-safety changes
  need review.
- `upstream = github.com/archimatetool/archi-modelrepository-plugin` is
  unmerged/diverged; these fixes are fork-local for now (see the separate
  rebase plan).
