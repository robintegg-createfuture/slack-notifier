---
name: tag-release
description: Create the next whole-number Git tag for this repository's release from the latest origin/main, for example v1, v2, or v3. Use when asked to tag, cut, or publish a release; do not create point-release tags such as v1.0.0.
---

# Tag Release

Create a release by pushing a new whole-number tag that points at the latest
`origin/main`. The repository's release workflow runs on pushed `v*` tags and
verifies and packages the Java application, then creates a GitHub Release
with `slack-notifier.jar` and `checksums.txt`. Running the jar requires Java 21
or later.

1. Fetch the latest remote refs:

   ```bash
   git fetch origin
   ```

2. Select the next unused sequential tag (`v1`, `v2`, `v3`, ...). Do not use
   point releases such as `v1.0.0`. Git will refuse to create a tag that
   already exists.
3. Create the tag at `origin/main` and push that exact same tag. For the first
   release:

   ```bash
   git tag v1 origin/main
   git push origin v1
   ```

   For later releases, replace `v1` with the next unused tag in both commands.
4. Report that the pushed tag triggers the GitHub Actions release workflow;
   do not manually build or upload release assets.
