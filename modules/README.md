# Modules

`modules/` is the canonical home for consolidated Gradle modules.

The repository is organized for worktree-based parallel development, with
stable shared modules wired through `gradle/repo-layout.properties`.

Current layout also includes `modules/integration/tests` for cross-module smoke
and pipeline verification.
