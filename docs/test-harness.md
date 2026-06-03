# Test harness

## Phase 1 feasibility spike (2026-06-03)
- Server reached `Done (`: yes
- Plugin loaded (`FlashbackServer enabled.`): yes
- Approx cold-boot time: 8 seconds
- Blockers: none

## Running tests
- Fast unit tests (no network, no server): `./gradlew test`
- Integration tests (boots a real Paper server + headless bot): `./gradlew integrationTest`
  - Downloads a Paper 1.21.5 jar once into `build/test-server/`.
  - Requires network access and a free local port.
