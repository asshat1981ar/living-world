# Patches that need a credential this environment does not have

## `ci-android-sdk-36.patch`

**What it does.** Makes CI install the Android SDK the project compiles against: `platforms;android-36` and
`build-tools;36.0.0` instead of 34.

**Why it is a patch.** The pushing App token has no `workflows` permission, so a push that touches
`.github/workflows/ci.yml` is refused.

**Whether it is needed.** Probably not: AGP downloads the missing platform itself ("Installing Android SDK Platform 36"),
so the build works on a runner that only has SDK 34. Keep this patch as the fallback for a runner whose SDK licences are
not accepted - if the CI run fails with "Failed to find target with hash string 'android-36'", apply it.

**How to apply.**

```sh
git apply docs/patches/ci-android-sdk-36.patch
git add .github/workflows/ci.yml
git commit -m "ci: install Android SDK 36"
git push
```

**Check it afterwards.** The next CI run must show `Install Android SDK 36` and the `Kotlin goldens` step passing.

## `ci-goldens-and-dependency-gate.patch`

**What it does.** Adds two gates to `.github/workflows/ci.yml`:

1. `Kotlin goldens` — runs the three scenarios with `--golden=tests/golden/<scenario>-10d.txt.gz` and fails on a
   mismatch. The runner exits 0 even when the dump differs, so the step checks the `Golden : OK` line itself.
2. `Dependency isolation gate` — runs `python3 tools/check_invariants.py --base "$CI_BASE_REF"`, which diffs the PR
   against its base commit and fails when a dependency or plugin line was added without `LW_ALLOW_DEPS=1`.

**Why it is a patch and not a commit.** The GitHub App that pushes this repository has no `workflows` permission, so
GitHub refuses any push that touches `.github/workflows/` (“refusing to allow a GitHub App to create or update
workflow `.github/workflows/ci.yml` without `workflows` permission”). The change is therefore kept here, and applied by
someone whose credential can push workflow files.

**How to apply.**

```sh
git apply docs/patches/ci-goldens-and-dependency-gate.patch
git add .github/workflows/ci.yml
git commit -m "ci: compare Kotlin output with the committed goldens and enforce the dependency gate"
git push
```

**How to check it afterwards.** `python3 tools/check_invariants.py --base origin/main` must pass locally, the three
`:desktopApp:run --args='10 0 --golden=…'` runs must print `Golden : OK`, and the next CI run on the branch must show
the two new steps.
