# Versioning Strategy and Build Git Tag Documentation

This document describes the versioning strategy and build git tag generation.
The table below maps the input git version and branches to the expected version and new build tag.

## Versioning Strategy Table

| Description | Git Show Version | Commit in Branches | Release version | New Build Tag |
|-------------|------------------|-------------------|-----------------|---------------|
| History style - init alpha tag on master | v8.5.0-alpha | master | v8.5.0-alpha | |
| History style - new commit on master | v8.5.0-alpha-2-g1234567 | master | v8.5.0-alpha-2-g1234567 | |
| History style - create release branch after alpha | v8.5.0-alpha | master, release-8.5 | v8.5.0-pre | v8.5.0 |
| History style - release branch with commits after alpha | v8.5.0-alpha-2-g1234567 | master, release-8.5 | v8.5.0-pre | v8.5.0 |
| History style - GA tag | v8.5.0 | release-8.5 | v8.5.0 | |
| History style - commits after GA | v8.5.0-2-g1234567 | release-8.5 | v8.5.1-pre | v8.5.1 |
| New style - alpha tag on master | v9.0.0-alpha | master | v9.0.0-alpha | |
| New style - commits after alpha | v9.0.0-alpha-2-g1234567 | master | v9.0.0-alpha-2-g1234567 | |
| New style - beta.0.pre tag | v9.0.0-beta.0.pre | master | v9.0.0-beta.0.pre | |
| New style - commits after beta.0.pre | v9.0.0-beta.0.pre-2-g1234567 | master | v9.0.0-beta.0.pre-2-g1234567 | |
| New style - beta release branch creation | v9.0.0-beta.0.pre | master, release-9.0-beta.0 | v9.0.0-beta.0.pre | v9.0.0-beta.0 |
| New style - beta release with commits | v9.0.0-beta.0.pre-2-g1234567 | master, release-9.0-beta.0 | v9.0.0-beta.0.pre | v9.0.0-beta.0 |
| New style - beta release | v9.0.0-beta.0 | release-9.0-beta.0 | v9.0.0-beta.0 | |
| New style - commits after beta release | v9.0.0-beta.0-2-g1234567 | release-9.0-beta.0 | v9.0.0-beta.0-2-g1234567 | |
| New style - rc.0.pre tag | v9.0.0-rc.0.pre | master | v9.0.0-rc.0.pre | |
| New style - commits after rc.0.pre | v9.0.0-rc.0.pre-2-g1234567 | master | v9.0.0-rc.0.pre-2-g1234567 | |
| New style - rc release branch creation | v9.0.0-rc.0.pre | master, release-9.0 | v9.0.0-rc.0.pre | v9.0.0-rc.0 |
| New style - rc branch with commits | v9.0.0-rc.0.pre-2-g1234567 | release-9.0 | v9.0.0-rc.0.pre | v9.0.0-rc.0 |
| New style - rc release | v9.0.0-rc.0 | release-9.0 | v9.0.0-rc.0 | |
| New style - commits after rc release | v9.0.0-rc.0-2-g1234567 | release-9.0 | v9.0.0-rc.1.pre | v9.0.0-rc.1 |
| New style - GA pre-release | v9.0.0-pre | release-9.0 | v9.0.0-pre | v9.0.0 |
| New style - commits after GA pre | v9.0.0-pre-2-g1234567 | release-9.0 | v9.0.0-pre | v9.0.0 |
| New style - GA release | v9.0.0 | release-9.0 | v9.0.0 | |
| New style - commits after GA | v9.0.0-2-g1234567 | release-9.0 | v9.0.1-pre | v9.0.1 |
| History style - hotfix without tag | v8.5.1-2-g1234567 | release-8.5-20250101-v8.5.1 | v8.5.1-2-g1234567 | |
| History style - hotfix with tag | v8.5.1-20250101-fecba32 | release-8.5-20250101-v8.5.1 | v8.5.1-20250101-fecba32 | |
| **Feature branch - prerelease** | v9.0.0-beta.1.pre-151-gb4c8f4dc8 | feature/fts | v9.0.0-feature.fts | v9.0.0-feature.fts |
