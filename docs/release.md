# Publishing the Android Provider to Maven Central

The manually dispatched [release workflow](../.github/workflows/release-candidate.yml) follows the Android SDK release process: validate the selected tag, verify the actual versioned Maven artifact with independent consumers, stage a signed publication, package it, upload once, and wait for Central to report `PUBLISHED`.

This workflow is release infrastructure, not release approval. OpenFeature 0.9.0 G02 is a documented upstream limitation outside the current FeatBit release-fix scope. Maintainers must review the FeatBit-owned outstanding checks in [verification](verification.md), including device/live-server evidence, before dispatching a public release. Ordinary pushes, PRs, and tag creation do not upload anything.

## Credentials and repository setup

Keep a GitHub Environment named `release` for reviewer/tag protection rules. Configure these four **organization Actions Secrets** under organization Settings > Secrets and variables > Actions. Use selected-repository access and authorize both `featbit-android-client-sdk` and `openfeature-provider-android-client`:

| Secret | Value |
| --- | --- |
| `SIGNING_KEY` | ASCII-armored PGP private signing key; preserve the header, footer, and newlines. |
| `SIGNING_PASSWORD` | Passphrase for that private key. |
| `CENTRAL_TOKEN_USERNAME` | Username from a Central Portal user token. |
| `CENTRAL_TOKEN_PASSWORD` | Password from that same user token. |

The Central account must have publishing rights for `co.featbit`, and the signing public key must be discoverable as required by Central. These are organization Secrets, not Variables. Remove same-name repository and `release` environment secrets so they do not override organization values. The workflow uses the standard `secrets` context; GitHub does not provide an organization-only selector. Environment approval protects this release job, but organization secrets are also available to other eligible workflows in authorized repositories. Never commit them. Normal builds and acceptance runs require no credentials. Gradle loads the PGP key in memory only when `-PsignPublication=true` is requested.

## Version and artifacts

Coordinates: `co.featbit:featbit-openfeature-provider-android:<version>`. The default remains `0.1.0-SNAPSHOT`. `-PproviderVersion=<version>` controls the provider publication and Java/Kotlin consumer dependency versions; `tools/acceptance.py --version <version>` propagates the same value through publication, metadata checks, and consumers.

The publication includes the AAR, POM, Gradle module metadata, sources JAR, and a `javadoc`-classifier JAR containing generated Dokka HTML. The POM contains license, SCM, and developer information. Dokka 2.2.0 generates the documentation using its v2 Gradle integration. Release signatures are detached armored PGP signatures.

Local unsigned rehearsal (no upload):

```powershell
python -B -m unittest discover -s tools -p 'test_*.py'
python tools/acceptance.py --version 0.1.0
```

The example version is for testing the release path; it does not select or authorize the first public release. Acceptance creates a fresh repository and runs Provider tests, API/metadata checks, and Java/Kotlin consumer tests, Debug/Release builds and lint. A complete successful consumer build does not prove device or live-server behavior.

## Dispatch

After the release gates are satisfied:

1. Commit the intended source and verify CI on that commit.
2. Create/push the matching `v<version>` tag. Do not move an existing release tag.
3. Open **Publish Android Provider to Maven Central**, select that tag, and enter the same version (without `v`). Branch selections, mismatched tags, invalid versions and snapshots are rejected.
4. Approve the `release` environment if the repository requires it.

Dispatching this workflow authorizes automatic publication after Central validation. It does not stop at a manually reviewed Central staging deployment. Same-version runs are serialized and do not cancel an active run.

The workflow verifies an unsigned publication first, stages the signed version separately, and compares every artifact byte-for-byte with the accepted publication. Packaging requires signatures and matching MD5/SHA-1/SHA-256/SHA-512 checksums for all five artifacts and their signatures. It includes only those files under `co/featbit/featbit-openfeature-provider-android/<version>/`; repository-level metadata and unrelated versions are excluded. The ZIP is reopened and its bytes compared with the staged files. Packaging checks signature presence/format; Central validates the signatures. Local signing smoke tests can additionally verify them with GPG.

## Evidence and recovery

The workflow retains:

- `maven-central-bundle`: the actual inner ZIP sent to Central. Extract the GitHub artifact download once to obtain it.
- `signed-release-candidate`: the staged Maven repository.
- `release-acceptance-evidence`: build/test logs, API/publication evidence and consumer reports.
- `central-publication-result`: deployment ID, bundle hash, latest state and sanitized errors.

Upload uses `publishingType=AUTOMATIC`; success requires `PUBLISHED`. Merely reaching `VALIDATED` is not success. Status polling has bounded requests and a 30-minute budget; transient network/429/5xx status failures are retried. Upload is never retried automatically because an unanswered request may have succeeded. Redirects are not followed and credentials are redacted from recorded errors.

If upload/status times out, inspect the Portal and recorded deployment ID before rerunning. Central may still publish after a local failure or workflow cancellation. Published versions are immutable; do not overwrite or blindly re-upload. After `PUBLISHED`, independently verify fresh downloads and Java/Kotlin consumption from Maven Central before announcing availability or replacing development installation instructions.

References: [Central Publisher API](https://central.sonatype.org/publish/publish-portal-api/), [Central requirements](https://central.sonatype.org/publish/requirements/), and [Dokka Gradle documentation](https://kotlinlang.org/docs/dokka-gradle.html).
