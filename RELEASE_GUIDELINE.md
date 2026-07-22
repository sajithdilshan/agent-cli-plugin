# Release Guideline

How to version the Agent CLI plugin and how releases are published to the JetBrains
Marketplace via GitHub Actions.

## TL;DR

1. Update `pluginVersion` in `gradle.properties`.
2. Add a matching `changelog/<version>.md` and a `<h3>` block in `plugin.xml` change-notes.
3. Merge to `main`. The push triggers CI, which builds, signs, uploads to the Marketplace, and cuts a `v<version>` GitHub release.

The **single source of truth for the version is `gradle.properties` → `pluginVersion`.**
The IntelliJ Gradle plugin patches `plugin.xml`'s `<version>` from it at build time.

## Versioning scheme

Versions follow `MAJOR.MINOR.PATCH` with an optional pre-release suffix:

```
1.0.0            stable
1.1.0-eap.3      early-access preview, build 3
1.1.0-beta       beta
1.2.0-rc.1       release candidate
```

- **MAJOR** — breaking changes or a platform-compatibility jump (e.g. new `sinceBuild`).
- **MINOR** — new features, backwards compatible.
- **PATCH** — bug fixes only.
- **Suffix** (after `-`) — marks a pre-release and, importantly, **decides the Marketplace channel** (see below).

## Marketplace channels ← version suffix

The publish channel is derived from the version suffix in `build.gradle.kts`:

```kotlin
val channel = versionValue.substringAfter('-', "").substringBefore('.').ifEmpty { "default" }
```

| pluginVersion   | Suffix    | Channel     | Who sees it                                   |
| --------------- | --------- | ----------- | --------------------------------------------- |
| `1.0.0`         | (none)    | `default`   | Everyone (stable, auto-updates)               |
| `1.1.0-eap.3`   | `eap.3`   | `eap`       | Only users subscribed to the `eap` channel    |
| `1.1.0-beta`    | `beta`    | `beta`      | Only users subscribed to the `beta` channel   |
| `1.2.0-rc.1`    | `rc.1`    | `rc`        | Only users subscribed to the `rc` channel     |

Non-default channels are **not** installed by default. To use one, a user adds the custom
repository URL in *Settings → Plugins → ⚙ → Manage Plugin Repositories*:

```
https://plugins.jetbrains.com/plugins/<channel>/list
```

## Branch strategy

The channel is decided by the **version suffix**, not the branch. Branches exist to
organize *work*; the suffix decides *where it ships*. Recommended model:

- **`main`** — the release branch. Only production-ready commits land here. Every push that
  changes `pluginVersion` triggers a publish. Use stable versions (`1.0.0`) for stable
  releases, or pre-release suffixes (`1.1.0-eap.1`) to ship a preview from `main`.
- **feature branches** (`feature/*`, `fix/*`) — normal development. Do **not** bump
  `pluginVersion` here; CI does not publish from these branches. Open a PR into `main`.
- **`eap` / `release/*`** (optional) — if you want to develop the next major line in
  parallel with stable patches, keep it on a long-lived branch. See "Publishing from a
  second branch" below — it requires a one-line workflow change.

### Example flows

**Ship a stable patch:**
```
git switch main
# edit code, then bump:
#   pluginVersion = 1.0.1
git commit -am "fix: …, bump to 1.0.1"
git push        # → publishes 1.0.1 to `default`
```

**Ship an EAP preview from main:**
```
#   pluginVersion = 1.1.0-eap.1
git commit -am "feat: …, 1.1.0-eap.1"
git push        # → publishes to `eap` channel; stable users unaffected
```

Iterate `-eap.1 → -eap.2 → …`, then drop the suffix (`1.1.0`) for the stable release.

## How the auto-release is wired (`.github/workflows/publish.yml`)

**Triggers:**
- `push` to `main` that modifies `gradle.properties`.
- `workflow_dispatch` — the manual "Run workflow" button (only users with write access).

**Job 1 — `check-version`:** reads `pluginVersion` from `gradle.properties`.
- On a manual run: skips the diff and always proceeds.
- On a push: compares against `HEAD~1`. Publishes only if the version actually changed —
  so editing another property in `gradle.properties` won't trigger a release.

**Job 2 — `publish`** (runs only if the version changed):
1. Sets up JDK 17 + Gradle.
2. Writes the signing cert/key from secrets to temp files.
3. Runs `./gradlew publishPlugin` — which builds, signs, and uploads to the channel
   derived from the version suffix.
4. Deletes the signing material.
5. Creates a `v<version>` GitHub release with auto-generated notes.

### Required repository secrets

Set under *Settings → Secrets and variables → Actions*:

| Secret                 | Source                                                              |
| ---------------------- | ------------------------------------------------------------------- |
| `PUBLISH_TOKEN`        | Marketplace token — https://plugins.jetbrains.com/author/me/tokens  |
| `CERTIFICATE_CHAIN`    | Full contents of your signing `chain.crt`                           |
| `PRIVATE_KEY`          | Full contents of your signing `private.pem`                         |
| `PRIVATE_KEY_PASSWORD` | Passphrase for the private key (empty if the key is unencrypted)    |

`GITHUB_TOKEN` is provided automatically; the job grants it `contents: write` for the release.

### Publishing from a second branch

The workflow currently watches only `main`. To also auto-publish from another long-lived
branch (e.g. `eap`), add it to the trigger:

```yaml
on:
  push:
    branches: [main, eap]
    paths:
      - gradle.properties
```

Keep that branch's `pluginVersion` on a pre-release suffix so it ships to a non-default
channel and never overwrites the stable release.

## Pre-release checklist

- [ ] `pluginVersion` bumped in `gradle.properties` (with the right suffix for the target channel).
- [ ] `changelog/<version>.md` added.
- [ ] `<h3><version></h3>` block added to the `change-notes` in `plugin.xml`.
- [ ] `sinceBuild` / `untilBuild` in `gradle.properties` still cover the target IDEs.
- [ ] `./gradlew buildPlugin` passes locally (runs tests + ktlint).
- [ ] `./gradlew verifyPlugin` reviewed for new API-compatibility warnings.

## Manual release / recovery

If CI is unavailable, publish from your machine (requires `local.properties` signing paths
and `PUBLISH_TOKEN` in the environment):

```bash
PUBLISH_TOKEN=<token> ./gradlew publishPlugin
```

The very first submission that registers a new plugin ID must be done once through the
Marketplace web UI; the API can only update an already-listed plugin.

## First-run caveat

The `workflow_dispatch` button only appears once `publish.yml` exists on the **default
branch** (`main`). Push the workflow to `main` before expecting the manual trigger.
