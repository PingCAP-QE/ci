# Versioning Strategy and Build Git Tag Documentation

This document describes the versioning strategy and build git tag generation.
The table below maps the input git version and branches to the expected version and new build tag.

## Versioning Strategy Table

| Description                                                                 | Git Show Version                  | Commit in Branches                     | Release version            | New Build Tag       |
|-----------------------------------------------------------------------------|------------------------------|----------------------------------------|----------------------------|----------------------|
| History style - init alpha tag on master branch                             | v8.5.0-alpha                 | master                                 | v8.5.0-alpha               |                      |
| History style - new commit on master branch                                 | v8.5.0-alpha-2-g1234567      | master                                 | v8.5.0-alpha-2-g1234567    |                      |
| History style - create new release branch but no new commit after alpha tag | v8.5.0-alpha                 | master, release-8.5                    | v8.5.0-alpha               | v8.5.0               |
| History style - create new release branch with new commits after alpha tag  | v8.5.0-alpha-2-g1234567      | master, release-8.5                    | v8.5.0-alpha-2-g1234567    | v8.5.0               |
| History style - added GA tag                                                | v8.5.0                       | release-8.5                            | v8.5.0                     |                      |
| History style - has new commits after GA tag                                | v8.5.0-2-g1234567            | release-8.5                            | v8.5.1-pre                 | v8.5.1               |
| New style - create alpha tag                                                | v9.0.0-alpha                 | master                                 | v9.0.0-alpha               |                      |
| New style - new commits after alpha tag (current state)                     | v9.0.0-alpha-2-g1234567      | master                                 | v9.0.0-alpha-2-g1234567    |                      |
| New style - create beta pre tag on master branch                            | v9.0.0-beta.0.pre            | master                                 | v9.0.0-beta.0.pre          |                      |
| New style - new commits after beta pre tag on master branch                 | v9.0.0-beta.0.pre-2-g1234567 | master                                 | v9.0.0-beta.0.pre-2-g1234567|                      |
| New style - created beta release branch with no new comments after beta pre tag | v9.0.0-beta.0.pre            | master, release-9.0-beta.0             | v9.0.0-beta.0.pre          | v9.0.0-beta.0        |
| New style - created beta release branch after new comments after beta pre tag | v9.0.0-beta.0.pre-2-g1234567 | master, release-9.0-beta.0             | v9.0.0-beta.0.pre-2-g1234567| v9.0.0-beta.0        |
| New style - release beta version                                            | v9.0.0-beta.0                | release-9.0-beta.0                     | v9.0.0-beta.0              |                      |
| New style - new commits after released beta version, we will do nothing for it | v9.0.0-beta.0-2-g1234567     | release-9.0-beta.0                     | v9.0.0-beta.0-2-g1234567   |                      |
| New style - create rc pre tag on master branch                              | v9.0.0-rc.0.pre              | master                                 | v9.0.0-rc.0.pre            |                      |
| New style - some new commits after rc pre taged on master branch            | v9.0.0-rc.0.pre-2-g1234567   | master                                 | v9.0.0-rc.0.pre-2-g1234567 |                      |
| New style - checkout release-X.Y branch after rc pre taged                  | v9.0.0-rc.0.pre              | master, release-9.0                    | v9.0.0-rc.0.pre            | v9.0.0-rc.0          |
| New style - some new commits after rc pre taged on release-X.Y branch       | v9.0.0-rc.0.pre-2-g1234567   | release-9.0                            | v9.0.0-rc.0.pre-2-g1234567 | v9.0.0-rc.0          |
| New style - create rc tag on release-X.Y branch                             | v9.0.0-rc.0                  | release-9.0                            | v9.0.0-rc.0                |                      |
| New style - new commits after a rc version released on release-X.Y branch   | v9.0.0-rc.0-2-g1234567       | release-9.0                            | v9.0.0-rc.0-2-g1234567     | v9.0.0-rc.1          |
| New style - prepare the GA release on release-X.Y branch                    | v9.0.0-pre                   | release-9.0                            | v9.0.0-pre                 | v9.0.0               |
| New style - new commits after GA pre tag created on release-X.Y branch      | v9.0.0-pre-2-g1234567        | release-9.0                            | v9.0.0-pre-2-g1234567      | v9.0.0               |
| New style - release GA version on release-X.Y branch                        | v9.0.0                       | release-9.0                            | v9.0.0                     |                      |
| New style - new commits after released GA version on release-X.Y branch     | v9.0.0-2-g1234567            | release-9.0                            | v9.0.1-pre                 | v9.0.1               |
| History style - hotfix branch without hotfix tags                           | v8.5.1-2-g1234567            | release-8.5-20250101-v8.5.1            | v8.5.1-2-g1234567          |                      |
| History style - hotfix branch with hotfix tag                               | v8.5.1-20250101-fecba32      | release-8.5-20250101-v8.5.1            | v8.5.1-20250101-fecba32    |                      |
