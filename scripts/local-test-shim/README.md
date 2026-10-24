# Local test shim

Minimal stand-in for `kotlin.test` plus a reflective runner. It exists only so the `core` unit tests can be
executed with `kotlinc` and a JDK when Gradle or Maven Central are not reachable. It is not part of any Gradle
source set and is never shipped. The authoritative run is `gradle :core:test` in CI (real JUnit 5).
