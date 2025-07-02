folder('ti-community-infra') {
    properties {
        folderLibraries {
            libraries {
                libraryConfiguration {
                    // An identifier you pick for this library, to be used in the @Library annotation.
                    name('tipipeline')
                    retriever {
                        modernSCM {
                            scm {
                                github {
                                    configuredByUrl(true)
                                    // Specify the HTTPS URL of the GitHub Organization / User Account and repository.
                                    repositoryUrl('https://github.com/PingCAP-QE/ci')

                                    // useless but required.
                                    repoOwner('PingCAP-QE')
                                    repository('ci')
                                }
                            }
                            // A relative path from the root of the SCM to the root of the library.
                            libraryPath('libraries/tipipeline')
                        }
                    }
                    // If checked, scripts may select a custom version of the library by appending @someversion in the @Library annotation.
                    allowVersionOverride(true)
                    // If checked, versions fetched using this library will be cached on the controller.
                    cachingConfiguration {
                        // Determines the amount of time until the cache is refreshed.
                        refreshTimeMinutes(60)
                        // Space separated list of versions to exclude from caching via substring search using .contains() method.
                        excludedVersionsStr('feature/ fix/ bugfix/')
                    }
                    // A default version of the library to load if a script does not select another.
                    defaultVersion('main')
                    // If checked, scripts will automatically have access to this library without needing to request it via @Library.
                    implicit(false)
                    // If checked, any changes in the library will be included in the changesets of a build, and changing the library would cause new builds to run for Pipelines that include this library.
                    includeInChangesets(true)
                }
            }
        }
    }
}
