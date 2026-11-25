# OCI image

Covers the `Dockerfile` at the repository root, the `.dockerignore` next to it, the
`DockerfileTest` that validates their structure, and the
`image` job of the [CI workflow](../../.github/workflows/ci.yml). The document records
why the image is built the way it is. `README.md` explains how to run the edge. The Helm chart of
TODO.md item 3 deploys the image on Kubernetes.

## Purpose and scope

The image is the packaging that runs the standalone edge on Kubernetes. The
constraints that bound the design:

- The edge is a self-contained shaded JAR, `target/sensepitch-edge-*-with-dependencies.jar`,
  produced by the shade plugin with `org.sensepitch.edge.Main` as the main class. `java -jar`
  runs it with the configuration file as the only argument (`Main`).
- The project targets Java 25 (`maven.compiler.release` in `pom.xml`) and is built and tested
  with Temurin 25 (`distribution: temurin` in `.github/workflows/ci.yml`).
- The shaded JAR bundles native code. `ShadedJarIT` checks the `netty-tcnative-boringssl-static`
  libraries shipped in the JAR: ELF for linux x86_64 and aarch_64, Mach-O for macOS and PE for
  Windows. There is no musl build, so the runtime image must be glibc-based.
- Kubernetes sends `SIGTERM` to PID 1 of the container, so `java` must be PID 1 for the graceful
  shutdown in `README.md` to run; that means exec-form `ENTRYPOINT`, no shell in between.
- The pod must run without privileges: the Helm chart sets `runAsUser`/`runAsGroup` 65532.

## Overview

One stage on `eclipse-temurin:25-jre`, pinned by digest. The `build` job of CI runs the full gate
`./mvnw clean verify` and uploads the shaded JAR as an artifact; for pushes to `main` and `v*`
tags, the `image` job downloads that artifact, reads the version from its manifest and builds the
image. The build context is that JAR alone (`.dockerignore`); the Dockerfile copies it under the
stable name `/app/sensepitch-edge.jar`, switches to the non-root user and sets the exec-form entry
point. The image contains only the JRE and the JAR, and is tagged with the JAR's version.

## Design decisions

### Temurin JRE on glibc, not Alpine

The runtime image is `eclipse-temurin:25-jre`. Alpine is smaller, but its musl libc cannot load
the bundled tcnative libraries (see the native library list in `ShadedJarIT`, verified against
the shaded JAR at build time). Temurin is also the distribution the project builds and tests with,
so the runtime JRE and the build JDK stay on the same vendor and patch cadence. Consequence: the
image is Ubuntu-sized, but no glibc workaround is needed and the native TLS code path is the same
one the tests exercise.

### The image packages the verified JAR, it builds nothing

The image contains the JAR that `./mvnw clean verify` built and tested in the `build` job of the
same CI run, handed to the `image` job as an artifact. Each push is verified once. A second build inside the image (the former multi-stage Dockerfile, see
Alternatives considered) would run in another environment without tests, so the shipped JAR
would not be the tested one. The rule is in `AGENTS.md` ("Build once, ship what was verified").
Consequence: a local `docker build` needs a prior `./mvnw package`, and `target/` must hold a
single shaded JAR (`./mvnw clean package`), otherwise the `COPY` fails.

### The version comes from pom.xml and the build commit

`pom.xml` is the only place that names the version. A snapshot is made unique by the build
commit, the property `edge.build.ref`, `HEAD` unless CI passes another: `0.1-SNAPSHOT` with build
commit `dbe0c1a` committed 2026-11-12 15:30:45 UTC becomes `0.1-20261112.153045-dbe0c1a`
(`edge-build-commit` executions of the exec and properties plugins, then the build-helper plugin,
`edge.build.version`). Commit time instead of build time: every build of the same commit gets the
same version. The version is written to the JAR manifest (`Implementation-Version`, also the OTLP
`service.version`) and the workflow reads it from there, so the image tag is the version the
running edge reports.

### A commit that changes no build input keeps the version

Until 2026-11-25 the version named `HEAD`, so a commit that changed only documentation gave a new
version, a different manifest and a new image. The `build` job now finds the build commit with
`git log -1 -- . ':(exclude)…'` and passes it as `-Dedge.build.ref`. All paths count except a list
of those that change neither JAR nor image: Markdown in the root, `doc/`, `plans/`, `charts/`,
`performance-test/`, `src/test/`, the other workflows, Dependabot. An exclude list and not a list
of inputs, because the two mistakes differ: a new input missing from an include list would change the JAR under the
old version; a new non-input missing from the exclude list only gives a needless new version.
`ci.yml` counts as an input, it sets the JDK and builds the image. Tests are not an input: a
commit that only adds tests verifies the same JAR again.

The list is in the workflow, not in `pom.xml`: which commit names a published version is a
decision of the publishing build. A local build keeps the simple rule, the version of `HEAD`, and
needs no history. CI checks out the full history (`fetch-depth: 0`): in a shallow clone the
oldest commit seems to add every file, so the build commit would be `HEAD` again.

The exec plugin calls the `git` binary for the commit time in two formats, the version's and the
epoch seconds of `project.build.outputTimestamp`; the former git-commit-id plugin gives one.

### The JAR and the image are reproducible

The same build commit gives a byte-identical JAR and the same image digest, so pushing it again
publishes no new image.

- JAR: `project.build.outputTimestamp` is the build commit time, jar and shade plugins write it
  as the time of every entry; the manifest has `Build-Jdk-Spec` (25), not the JDK patch.
  Checked 2026-11-25 with Temurin 25.0.5 and Maven 3.9.11: two `./mvnw clean package` of the same
  commit gave the same SHA-256 (before the change they differed).
- Image: the base is pinned by digest, Dependabot proposes the new digest weekly and the change
  of the `Dockerfile` is a new version. The `image` job sets `SOURCE_DATE_EPOCH` to the build
  commit time (handed over from the `build` job, read from the file the exec plugin wrote) and
  exports with `rewrite-timestamp=true`, so the config's `created` and the file times in the
  layers are that time. The layer also records the JAR's mode, which is the mode of the
  downloaded file unless `COPY --chmod=0444` fixes it.
  Checked 2026-11-25 with buildx 0.30.1 and the docker-container driver as in CI: four builds
  from the same JAR, each on a new builder, with different file times and modes 644, 600 and
  664, gave the same manifest digest, as OCI export and pushed to a local `registry:2`. Without
  `--chmod`, mode 664 gave another digest.
- Labels are the repository (links the package on ghcr.io) and the version only; the labels
  `docker/metadata-action` generates carry the run's time and commit. No provenance attestation:
  it records the workflow run, so its image index would be new on every run.

Before the push the job reads the digest the version's tag had; a different digest after the push
fails the job. This catches what the pins do not cover, e.g. a new JDK patch of `setup-java` that
compiles differently; by then the tag already points to the new image.

Image tags:

- always the exact version, e.g. `0.1-20261112.153045-dbe0c1a` or `0.1`;
- `main`, moving, for the newest build of `main`; the chart from the source tree pulls it;
- `latest`, moving, for the newest release.

A release is a commit whose `pom.xml` version has no `-SNAPSHOT`, tagged `v<version>`. The tag
only marks the commit: the workflow fails if the tag is not `v` plus the JAR's version. Steps: set
the pom to `0.1`, commit, tag `v0.1`, push both, set the pom to `0.2-SNAPSHOT`, commit. A packaged
chart gets the version with `helm package --app-version`.

### Non-root UID 65532 and exec-form ENTRYPOINT

`USER 65532:65532` matches the Helm chart's `runAsUser`/`runAsGroup`. The numeric IDs need no
`/etc/passwd` entry, so the same image works with the chart's `readOnlyRootFilesystem` and no
image-specific user database. `ENTRYPOINT ["java", "-jar", "/app/sensepitch-edge.jar"]` is exec
form: Docker starts `java` directly as PID 1, `SIGTERM` reaches it, and the shutdown sequence
described in `README.md` (accept grace, drain, request log) runs. The default `CMD`
`/etc/sensepitch/config/config.yml` matches the chart's `args`, so `docker run image <config>`
overrides it per container.

### Registry ghcr.io/sensepitch/sensepitch-edge

The registry and repository name match the Helm chart's `image.repository` default, so the chart
works once an image is published without overriding values. GitHub Container Registry keeps the
image next to the code, is free for public repositories, and authenticates with the workflow's
`GITHUB_TOKEN`.

### A structural test, because the Maven gate has no container runtime

`DockerfileTest` runs in `./mvnw test`. It parses the Dockerfile instructions as Docker would
join continued lines, then checks the single JRE stage without a Maven build, the copy of the
shaded JAR, the non-root user, the exec-form entry point and default command, and the exposed
ports. It also checks that `.dockerignore` admits only the shaded JAR, and that the workflow
publishes the chart's image from the downloaded artifact, tagged with the JAR's version, and names
no version itself. These
checks run without Docker or a network connection. The GitHub Actions runner builds the image
and pushes it to the registry.

## Alternatives considered

### distroless Java 25

Google's distroless Java images are Debian-based (glibc), so they would load the native
libraries. They ship no shell and no package manager, which reduces the attack surface but makes
`kubectl exec` debugging harder and rules out ad-hoc tooling in the image. The decision went to
Temurin JRE to keep the runtime on the same vendor as build and test; distroless remains a valid
alternative and was not measured offline (no network access in this environment).

### Alpine / musl

Rejected: the shaded JAR has no musl build of the tcnative library, so the TLS listener could
not start. The JAR would need musl natives built and added first, which is out of scope.

### Multi-stage build, the JAR built inside the image

Used until 2026-11-25: a `eclipse-temurin:25-jdk` stage ran `./mvnw package` without tests (the
Maven repository in a BuildKit cache mount, `.git` kept in the build context for the version
stamp), the JRE stage copied the JAR. It makes `docker build` work on a bare checkout, but the
shipped JAR was a second build, in another environment than the tested one and with a different
build time in its version. Dropped for packaging the verified JAR.

### A separate image workflow

Used until 2026-11-25: `build-and-push-image.yml` ran its own `./mvnw clean verify` on every push
to `main`, next to the same run in `ci.yml`. Dropped for the `image` job in `ci.yml`, which
verifies once and packages the artifact of that verification.

### Skip the image job for documentation-only pushes

`paths-ignore` on the push trigger would avoid the new image of a documentation commit with less
code. Rejected: the version would still name `HEAD`, a later build of the same JAR would differ
in its manifest, and a push mixing code and documentation commits is not ignored anyway.
Reproducibility answers the actual question, whether this image is the one already published.

### Skip the push when the version's tag exists

Keeps provenance, but the shipped image would be the first build, never checked against later
ones. A reproducible rebuild compared to the published digest verifies it.

### Version from the git tag

The `v*` tag name gave the image's semver tags (`docker/metadata-action` `type=semver`) while the
JAR reported the pom version, and nothing checked that they agreed. A tag-derived version
(`${revision}` with the flatten plugin) would make the tag the only source, at the cost of a
default revision for every local build and a pom that names no version. Dropped for the pom as the
source and a check of the tag.

## Limitations

- The JDK of the `build` job (`setup-java`, newest Temurin 25) is not pinned; a JAR is
  reproducible as long as that JDK compiles the same. The digest check above reports a change.
- No provenance attestation is published, see above.
- A local build has the version of `HEAD`, so after a documentation-only commit it differs from
  the CI build of that commit; `-Dedge.build.ref=<commit>` gives the CI version. A working tree
  with uncommitted changes reports the version of its last commit.
- The structural `DockerfileTest` in the Maven gate checks the image files. The publishing
  workflow builds the image. The container smoke test runs separately.
- The image is built for the runner architecture (`linux/amd64`). A multi-architecture manifest
  is left for when an arm64 fleet is needed.
- `docker build` needs a prior `./mvnw package`, see above.

## Planned improvements

- Build a multi-architecture manifest (linux/amd64, linux/arm64).
- Add an image smoke test in CI: run the image with a test config and assert `/healthz` and
  `/readyz` answer 200 and `SIGTERM` exits.
