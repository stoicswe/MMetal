# Contributing

First off, thank you so much for considering a contribution to this project. We welcome contributions from everyone!

<br/>

## Table of Contents

-   [1. How can I contribute?](#how-to-contribute)
-   [2. Guidelines](#guidelines)
    -   [2.1 Commit messages](#commit-messages)
    -   [2.2 Coding style guide](#coding-style-guide)
-   [3. Code Review Process](#code-review)
-   [4. Community and Communication](#community-and-communication)
-   [5. Releasing](#releasing)

<br/>

## How to Contribute?

Contributing is simple. Here's how you can do it:

1. **Identify an Issue**: Look for existing [Issues](https://github.com/stoicswe/MMetal/issues) or create your own explaining the feature or fix.
2. **Fork the Repository**: Click on the fork button in the top right corner.
3. **Clone the Repository**: After forking, clone the repo to your local machine to make changes.
4. **Set up Your Environment**: Set up the repository by following the Quick Start section of the [README.md](README.md). In short: macOS on Apple Silicon, Xcode command line tools, and a **HotSpot JDK 25+ (Temurin)** - IBM Semeru / OpenJ9 aborts inside the FFI machinery.
5. **Create a New Branch**: Before making any changes, switch to a new branch:
    ```bash
    # For bugs:
    git checkout -b bug/your-new-branch-name

    # For features:
    git checkout -b feature/your-new-branch-name
    ```
6. **Make Changes**: Implement your feature or fix.
7. **Build and Run**: `./gradlew build` (or `./scripts/build-local.sh`, which packages the jar into `dist/`) and `./gradlew runClient`. If you edit `src/main/resources/metal/MMetalRaytrace.metal`, recompile the shader - the Gradle build does it automatically on macOS (`xcrun metal`), otherwise run it manually and commit the updated `MMetalRaytrace.metallib`.
8. **Write Commit Messages**: Please try to make your commit messages adequately descriptive.
9. **Push to GitHub**: After committing your changes, push them to GitHub:
    ```
    git push origin your-new-branch-name
    ```
10. **Submit a Pull Request**: Go to your repository on GitHub and click the 'Compare & pull request' button. Fill in the details and submit.

<br/>

## Guidelines

### Commit Messages

While we do not have a distinct commit message style, it is best to ensure that your commit message contains enough description as to what the goal of the commit is.

### Coding Style Guide

We follow the standard Minecraft / Fabric modding conventions for Java:

-   **Tabs** for indentation, one statement per line.
-   Braces on the same line (`if (...) {`), `else` on the closing brace line.
-   Classes `PascalCase`, methods/fields `camelCase`, constants `UPPER_SNAKE_CASE`.
-   Package layout: `dev.mmetal.api` is the public plugin API, `dev.mmetal.metal.*` is internal.
-   Keep the render pipeline **fail-safe**: a failure logs a warning and falls back, it never
    takes the game down.
-   Metal struct layouts must stay in sync between `MMetalRaytrace.metal` and the Java offsets
    documented next to `EFFECT_PARAMS_SIZE`; update both in the same commit.

```java
public final class Example {
	private int value;

	public void update(int next) {
		if (next > 0) {
			value = next;
		} else {
			value = 0;
		}
	}
}
```

<br/>

### Code Review

All submissions, including submissions by project maintainers, require review. We use GitHub pull requests for this process. If your pull request is particularly urgent, please mention this in the request.

<br/>

### Community and Communication

Follow discussions in the [GitHub Issues](https://github.com/stoicswe/MMetal/issues) section of our repository. Please read the [Code of Conduct](CODE_OF_CONDUCT.md) first.

<br/>

## Releasing

Full releases are published as GitHub Releases:

1. Bump `mod_version` in `gradle.properties` if needed and commit it.
2. Create a release on GitHub with a tag such as `0.2.0` (bare version, no `v` prefix).
3. Publishing runs [`.github/workflows/build.yml`](.github/workflows/build.yml), which builds on
   `ubuntu-latest` with Temurin 25 and attaches the mod jar to the release.

Nightly snapshots are separate: [`.github/workflows/nightly.yml`](.github/workflows/nightly.yml)
runs once a day (06:00 UTC, plus manual dispatch) and only when `src/`, the Gradle files,
the workflows changed since the newest `nightly_*` tag. Each run publishes a new
pre-release tagged `nightly_<version>.<date>_<sha>` with the mod jar;
snapshots are never overwritten. Force one any time via **Actions → nightly → Run workflow**.
