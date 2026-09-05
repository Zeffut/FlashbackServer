# Test harness

## Phase 1 feasibility spike (2026-06-03)
- Server reached `Done (`: yes
- Plugin loaded (`FlashbackServer enabled.`): yes
- Approx cold-boot time: 8 seconds
- Blockers: none

## Running tests
- Fast unit tests (no network, no server): `./gradlew test`
- Integration tests (boots real Paper servers + a headless bot): `./gradlew integrationTest`
  - Downloads Paper 1.21.5 and Paper 26.2 jars once into temporary test directories.
  - Requires JDK 21 and JDK 25, network access, and free local ports.
  - Set `JAVA21_HOME` and `JAVA25_HOME` to portable JDK installations when they are not
    auto-detected (the CI workflow demonstrates this).
