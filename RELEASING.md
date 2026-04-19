# Releasing

Releases are driven by pushing a version tag. GitHub Actions handles everything from there.

## Tag format

Tags must be prefixed with `v` followed by a [semantic version](https://semver.org/):

```
v1.2.3
```

The `v` prefix is required — the release workflow strips it to produce the Maven artifact version (e.g. tag `v1.2.3` → JAR `maestro-logcat-sidecar-1.2.3.jar`).

## Steps

1. Ensure `main` is in the state you want to release.

2. Create and push the tag:

   ```sh
   git tag v1.2.3
   git push origin v1.2.3
   ```

3. The [`release.yml`](.github/workflows/release.yml) workflow fires automatically and:
   - Strips the `v` prefix to derive the release version (`1.2.3`)
   - Runs `mvn versions:set -DnewVersion=1.2.3` inside the CI job (not committed back to the repo)
   - Builds the fat JAR with `mvn package -DskipTests`
   - Creates a GitHub Release with auto-generated release notes and attaches `maestro-logcat-sidecar-1.2.3.jar`

The version in `pom.xml` on the branch remains `1.0-SNAPSHOT` between releases.
