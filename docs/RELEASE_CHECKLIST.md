# Release Checklist

Use this when cutting a stable release or a capture/photo preview. The tagged
workflow verifies the tag and retains an unsigned comparison APK. The owner
creates the GitHub release only after that workflow is green; the release-signed
APK is built and attached from the maintainer machine that holds the signing
key.

## 1. Confirm scope and branch

- Stable releases come from `main`.
- Capture/photo previews come from `preview/document-capture`.
- Fix branches must be merged into the intended release branch before tagging.
- Do not tag from a local branch with uncommitted changes.
- Keep `dist/`, local SDK checkouts, keystores, `keystore.properties`, and
  private test documents out of git.

## 2. Update version and public notes

- Bump `versionCode` and `versionName`.
- For a signed preview, update `releaseChannelLabel` and
  `isolatedVersionSuffix` in `app/build.gradle.kts` so About and isolated builds
  identify the preview and `versionCode`. Clear the channel label for stable.
- Run `sh tools/check_release_metadata.sh <tag>`; tagged CI repeats this check
  and stops before building if the tag, version, channel, or suffix is stale.
- Update the README stable or beta line.
- Update `CHANGELOG.md` with user-visible, security, privacy, and compatibility
  changes.
- For preview releases, describe what changed since the previous preview and
  what still needs hardware testing.
- If a change touches storage, backups, permissions, security boundaries, or
  release artifacts, update the relevant file under `docs/`.

## 3. Run local checks

Use JDK 17.

```sh
./gradlew test lint detekt assembleDebug assemblePreview assembleRelease
```

Then verify the release-signed APK:

```sh
tools/verify_release_apk.sh app/build/outputs/apk/release/app-release.apk
```

For a 0.15.x stable release, also build and verify the 32-bit companion APK for
Light Phone 2 (copy the arm64 APK aside first — both builds write the same
output path):

```sh
cp app/build/outputs/apk/release/app-release.apk dist/Paka-v<version>.apk
./gradlew assembleRelease -Ppaka.releaseAbi=armeabi-v7a --rerun-tasks
cp app/build/outputs/apk/release/app-release.apk dist/Paka-v<version>-arm32.apk
tools/verify_release_apk.sh dist/Paka-v<version>-arm32.apk
```

0.15.x is the last line to support Light Phone 2; later versions ship only the
arm64 APK.

Expected for 0.15:

- Signing certificate SHA-256:
  `098fbb0a5455ec00dbafae93f16bff74b048e5bde7a824fac9ecf42effad0019`
- Android permissions: `android.permission.CAMERA` only.
- No `android.permission.INTERNET`.
- No `android.permission.ACCESS_NETWORK_STATE`.

## 4. Hardware smoke test

Install the release-signed APK on the Light Phone III and run the short pass
from [DEVICE_TESTING.md](DEVICE_TESTING.md):

- Upgrade over the previous stable or preview.
- Open pass list, TOTP list, scanner, capture, PDF, photo, Details, and backup.
- Test capture/crop, imported-photo crop, PDF viewing, stack paging, and
  barcode rendering.
- Background and reopen from code, scanner, PDF, and photo screens.
- Confirm app/task background is black and return-home behaves as configured.
- Confirm haptics occur only on real actions and available page changes.
- Copy [DEVICE_TEST_RESULT_TEMPLATE.md](DEVICE_TEST_RESULT_TEMPLATE.md), record
  the candidate and device details, and leave anything not exercised as
  `NOT RUN` rather than assuming it passed.

## 5. Tag and wait for CI

Create an annotated tag after local checks pass.

Examples:

```sh
git tag -a v0.15.0-beta.4 -m "Paka 0.15.0 capture preview 4"
git push origin v0.15.0-beta.4
```

The tagged-release workflow will:

- run tests, lint, detekt, debug build, preview build, and release build;
- verify debug, preview, and unsigned release APK permissions;
- upload an unsigned release APK artifact for comparison.

Wait for the tagged-release workflow to finish successfully. Do not create or
publish a GitHub release while it is queued or running. If it fails, fix the
failure and cut a new version/tag; do not move a published tag. Signing stays
local.

## 6. Create the GitHub release manually as the owner

While signed in to the owner's GitHub account, create the release only after
the tagged workflow is green. This keeps the release author attributable to the
owner rather than `github-actions[bot]`.

For capture/photo previews:

- Title format: `Paka 0.15.0 capture preview N`
- Tag format: `v0.15.0-beta.N`
- Mark as **pre-release**.
- Do not mark as latest.
- Attach the release-signed APK as `Paka-v0.15.0-beta.N.apk`.
- Include an APK SHA-256 digest in the notes.
- Include the release certificate SHA-256 digest in the notes.
- Mention the source branch, usually `preview/document-capture`.
- Mention that `main` remains on the current stable line until stable 0.15.0.

For stable releases:

- Title format: `Paka X.Y.Z`
- Tag format: `vX.Y.Z`
- Do not mark as pre-release.
- Mark as latest only after the APK is attached and verified.
- Attach the release-signed APK as `Paka-vX.Y.Z.apk`.
- Include the APK SHA-256 digest and certificate SHA-256 digest.

## 7. Final checks after publishing

- Download the published APK asset and run `tools/verify_release_apk.sh` on that
  downloaded file, not only the local build output.
- Confirm the release is not still a draft.
- Confirm preview releases show the pre-release badge.
- Confirm the APK asset is visible under release assets.
- Confirm the release title matches the preview/stable naming convention.
- Confirm the changelog comparison link points between the intended tags.
