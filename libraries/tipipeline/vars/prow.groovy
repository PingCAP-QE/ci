def checkoutRefsWithCacheLock(refs, timeout = 5, credentialsId = '', withSubmodule = false, gitBaseUrl = 'https://github.com') {
    final lockResource = getCacheKey('git', refs)
    lock(lockResource) {
        checkoutRefsWithCache(refs, timeout, credentialsId, withSubmodule, gitBaseUrl)
    }
}

def checkoutRefsWithCache(refs, timeout = 5, credentialsId = '', withSubmodule = false, gitBaseUrl = 'https://github.com') {
    final cacheKey = getCacheKey('git', refs)
    final restoreKeys = getRestoreKeys('git', refs)
    cache(path: "./", includes: '**/*', key: cacheKey, restoreKeys: restoreKeys) {
        retry(2) {
            checkoutRefs(refs, credentialsId, timeout, withSubmodule, gitBaseUrl)
        }
    }
}

def checkoutRefs(refs, credentialsId = '', timeout = 5, withSubmodule = false, gitBaseUrl = 'https://github.com') {
    final explicitCredentialsId = credentialsId?.trim()
    final sshCredentialsId = explicitCredentialsId ?: (
        withSubmodule ? env.GIT_SSH_CREDENTIALS_ID?.trim() : ''
    )
    final cdnEnabled = env.GIT_CDN_ENABLED?.trim()?.toBoolean()
    final httpCredentialsId = env.GIT_HTTP_CREDENTIALS_ID?.trim()

    // Repositories owned by an App-installed organization can authenticate with
    // broker-minted installation tokens instead of the ti-chi-bot SSH key/PAT.
    // Keep the URL form unchanged (SSH on TKE so git-cdn still applies, HTTPS on
    // GCP where agents talk to GitHub directly) and only swap the credential
    // source, so the checkout cache keys and CDN behaviour stay identical.
    if (useBrokerCredentials(refs)) {
        final remoteUrl = cdnEnabled
            ? "git@github.com:${refs.org}/${refs.repo}.git"
            : publicRemoteUrl(refs, gitBaseUrl)
        withGitAskPassFromBroker([repo: "${refs.org}/${refs.repo}"]) {
            _checkoutRefsImpl(refs, remoteUrl, timeout, withSubmodule)
        }
        return
    }

    // Keep the existing URL selection: an explicit credential means that the
    // main repository uses SSH. TKE can rewrite that URL to the CDN, while
    // GCP continues to use the SSH agent directly.
    def checkout = {
        if (explicitCredentialsId) {
            checkoutPrivateRefs(refs, explicitCredentialsId, timeout, withSubmodule, gitBaseUrl)
        } else {
            checkoutPublicRefs(refs, timeout, withSubmodule, gitBaseUrl)
        }
    }

    def checkoutWithHttpCredentials = {
        if (cdnEnabled && httpCredentialsId) {
            withGitAskPass(httpCredentialsId, checkout)
        } else {
            checkout()
        }
    }

    // A job that only passes an empty credentialsId can still have private
    // SSH submodules. Use the cluster-provided default SSH credential for
    // the checkout scope without changing the public HTTPS main URL.
    if (sshCredentialsId && !explicitCredentialsId) {
        sshagent(credentials: [sshCredentialsId]) {
            checkoutWithHttpCredentials()
        }
    } else {
        checkoutWithHttpCredentials()
    }
}

/*
 * Let Git try an anonymous request first and provide a GitHub App installation
 * token minted by the in-cluster token broker only after the server asks for
 * credentials. The App private key stays in the broker; the agent only proves
 * its identity with the pod ServiceAccount token.
 *
 * Works for both transports the library uses: direct HTTPS to GitHub, and the
 * TKE git-cdn rewrite, because git-cdn forwards the BasicAuth header to GitHub.
 *
 * cfg:
 *   repo        owner/repository used when the git prompt carries no path.
 *   permissions JSON permission set, default {"contents":"read"}.
 *   brokerUrl   overrides GIT_BROKER_URL.
 */
def withGitAskPassFromBroker(Map cfg = [:], Closure body) {
    final brokerUrl = cfg.brokerUrl?.toString()?.trim() ?: env.GIT_BROKER_URL?.toString()?.trim()
    if (!brokerUrl) {
        throw new IllegalArgumentException(
            'withGitAskPassFromBroker requires cfg.brokerUrl or the GIT_BROKER_URL environment variable')
    }
    final repo = cfg.repo?.toString()?.trim() ?: ''
    final permissions = cfg.permissions ?: '{"contents":"read"}'
    // The controllers already provide git configuration through GIT_CONFIG_*
    // (git-cdn insteadOf rewrites). Append to it instead of resetting the count.
    final baseConfigCount = (env.GIT_CONFIG_COUNT?.toString()?.trim() ?: '0') as int
    final suffix = UUID.randomUUID().toString()
    final askPassScript = "${env.WORKSPACE}/git-askpass-broker-${suffix}"
    final cacheFile = "${env.WORKSPACE}/git-broker-token-${suffix}"

    try {
        writeFile(file: askPassScript, text: libraryResource('scripts/git_askpass_broker.sh'))
        sh label: 'Prepare Git HTTP credential helper (token broker)', script: "chmod 700 '${askPassScript}'"
        withEnv([
            "GIT_ASKPASS=${askPassScript}",
            'GIT_TERMINAL_PROMPT=0',
            "TIPIPELINE_BROKER_URL=${brokerUrl}",
            "TIPIPELINE_BROKER_REPO=${repo}",
            "TIPIPELINE_BROKER_PERMISSIONS=${permissions}",
            "TIPIPELINE_BROKER_CACHE=${cacheFile}",
            // Rewrite SSH dependency URLs to HTTPS, which the token can
            // authenticate, and include the repository path in credential
            // prompts so the helper can mint a per-repository token.
            "GIT_CONFIG_COUNT=${baseConfigCount + 2}",
            "GIT_CONFIG_KEY_${baseConfigCount}=url.https://github.com/.insteadOf",
            "GIT_CONFIG_VALUE_${baseConfigCount}=git@github.com:",
            "GIT_CONFIG_KEY_${baseConfigCount + 1}=credential.useHttpPath",
            "GIT_CONFIG_VALUE_${baseConfigCount + 1}=true",
        ]) {
            body()
        }
    } finally {
        sh label: 'Remove Git HTTP credential helper (token broker)', script: "rm -f '${askPassScript}' '${cacheFile}'"
    }
}

/*
 * Let Git try an anonymous HTTP request first and provide Jenkins' PAT only
 * after the server asks for credentials. The script is outside the workspace
 * because checkout cleanup and the pipeline cache operate on the workspace.
 *
 * The helper answers only for the hosts allow-listed inside
 * resources/scripts/git_askpass.sh (github.com and the in-cluster git-cdn);
 * every other host gets no credential, so a PR-controlled .gitmodules cannot
 * redirect the bot credential to an attacker host.
 */
def withGitAskPass(String credentialsId, Closure body) {
    // Keep the askpass script inside the shared workspace volume but outside
    // the Git worktree. Every caller clones into a workspace subdirectory via
    // dir(), so the workspace root is never touched by git clean/checkout.
    // Let writeFile create the file (it runs as the JNLP agent user); a file
    // pre-created by an sh step would be owned by the container's root user
    // and not writable by the agent, and a plain /tmp path would sit on a
    // different filesystem than the container that runs Git.
    final askPassScript = libraryResource 'scripts/git_askpass.sh'
    final tmpAskPassScript = "${env.WORKSPACE}/git-askpass-${UUID.randomUUID().toString()}"

    try {
        writeFile(file: tmpAskPassScript, text: askPassScript)
        sh label: 'Prepare Git HTTP credential helper', script: "chmod 700 '${tmpAskPassScript}'"
        withCredentials([usernamePassword(credentialsId: credentialsId, usernameVariable: 'TIPIPELINE_GIT_USERNAME', passwordVariable: 'TIPIPELINE_GIT_PASSWORD')]) {
            withEnv(["GIT_ASKPASS=${tmpAskPassScript}", 'GIT_TERMINAL_PROMPT=0']) {
                body()
            }
        }
    } finally {
        sh label: 'Remove Git HTTP credential helper', script: "rm -f '${tmpAskPassScript}'"
    }
}

def checkoutPublicRefs(refs, timeout = 5, withSubmodule = false, gitBaseUrl = 'https://github.com') {
    _checkoutRefsImpl(refs, publicRemoteUrl(refs, gitBaseUrl), timeout, withSubmodule)
}

// Whether the git base url param is a full URL or a hostname.
def publicRemoteUrl(refs, gitBaseUrl = 'https://github.com') {
    if (gitBaseUrl?.trim()?.startsWith('http')) {
        return "${gitBaseUrl}/${refs.org}/${refs.repo}.git"
    }
    return "https://${gitBaseUrl}/${refs.org}/${refs.repo}.git"
}

// Whether the repository should authenticate through the token broker.
//
// `GIT_BROKER_ORGS` is the organization allowlist (defaults to the
// SAML-enforcing tidbcloud org). `GIT_BROKER_REPOS` narrows the migration to
// single repositories of an organization that is not fully migrated, using
// `owner/repo` entries or `owner/*` wildcards. Everything else keeps the SSH
// key/PAT until it is migrated deliberately.
def useBrokerCredentials(refs) {
    if (!env.GIT_BROKER_ENABLED?.trim()?.toBoolean()) {
        return false
    }
    final org = refs?.org?.toString()?.trim()?.toLowerCase()
    if (!org) {
        return false
    }
    if (splitBrokerAllowlist(env.GIT_BROKER_ORGS ?: 'tidbcloud').contains(org)) {
        return true
    }
    final repo = refs?.repo?.toString()?.trim()?.toLowerCase()
    if (!repo) {
        return false
    }
    final slug = "${org}/${repo}"
    return splitBrokerAllowlist(env.GIT_BROKER_REPOS).any { entry ->
        entry == slug || entry == "${org}/*"
    }
}

def splitBrokerAllowlist(def value) {
    return (value ?: '')
        .toString()
        .split(',')
        .collect { it.trim().toLowerCase() }
        .findAll { it }
}

/*
* Checkout refs from private repository.
*
* depended on plugins:
*  - ssh-agent
*/
def checkoutPrivateRefs(refs, credentialsId, timeout = 5, withSubmodule = false, gitSshHost = 'github.com') {
    def gitBaseUrl = ""
    def knownHost = gitSshHost?.trim()
    if (knownHost?.startsWith('http')) {
        knownHost = knownHost.replaceFirst('^https?://', '')
        knownHost = knownHost.replaceFirst('/.*$', '')
    }
    if (knownHost?.trim()) {
        gitBaseUrl = "git@${knownHost}"
    } else {
        knownHost = 'github.com'
        gitBaseUrl = "git@${knownHost}"
    }

    final remoteUrl = "${gitBaseUrl}:${refs.org}/${refs.repo}.git"
    sshagent(credentials: [credentialsId]) {
        // GitHub SSH URLs are rewritten to the CDN in TKE, so avoid an
        // unnecessary GitHub network call there. Keep host-key setup for
        // direct SSH and non-GitHub SSH hosts.
        if (!env.GIT_CDN_ENABLED?.trim()?.toBoolean() || knownHost != 'github.com') {
            seedSshHostKeys(knownHost)
        }
        _checkoutRefsImpl(refs, remoteUrl, timeout, withSubmodule)
    }
}

// Record a host's SSH host keys before the checkout loads the deploy key.
//
// `ssh-keyscan` answers from the network: the first answer is trusted without
// any proof, so a party on the path can supply its own key and then read
// everything the agent signs with the bot's key. GitHub publishes its host keys
// over TLS at https://api.github.com/meta; fetch them from there instead and
// let ssh require a match.
def seedSshHostKeys(String host) {
    final safeHost = host?.trim() ?: ''
    if (!(safeHost ==~ /^[A-Za-z0-9.-]+$/)) {
        error("refusing to seed SSH host keys for '${host}': not a plain host name")
    }
    sh label: "Seed SSH host keys for ${safeHost}", script: """#!/usr/bin/env bash
        set -eu
        mkdir -p ~/.ssh && chmod 0700 ~/.ssh
        keys="\$(mktemp)"
        curl -fsSL --retry 3 --retry-delay 2 --connect-timeout 5 --max-time 30 \
            https://api.github.com/meta \
            | tr ',' '\\n' \
            | grep -oE '"(ssh-[a-z0-9]|ecdsa-sha2)[^"]*"' \
            | tr -d '"' \
            | sed 's/^/${safeHost} /' > "\${keys}"
        if [ ! -s "\${keys}" ]; then
            echo "ERROR: could not read GitHub's published SSH host keys for ${safeHost}" >&2
            exit 1
        fi
        cat "\${keys}" >> ~/.ssh/known_hosts
        rm -f "\${keys}"
    """
}

def _checkoutRefsImpl(refs, remoteUrl, timeout, withSubmodule) {
    // These values come from JOB_SPEC and end up in a shell script. Reject
    // anything that is not a git object name or a plain branch name so a
    // crafted value cannot turn into shell syntax.
    final shaPattern = /^[0-9a-f]{7,40}$/
    final refPattern = /^[A-Za-z0-9._\/-]+$/
    // The organization and repository are interpolated into the remote URL and
    // then into the shell, and the checkout runs with a credential scoped to
    // this repository. Neither name may contain a separator, whitespace or any
    // other character that could point the fetch somewhere else.
    final namePattern = /^[A-Za-z0-9._-]+$/
    if (!(refs.org?.toString()?.trim() ==~ namePattern)) {
        error("refusing to check out: org '${refs.org}' is not a plain GitHub organization name")
    }
    if (!(refs.repo?.toString()?.trim() ==~ namePattern)) {
        error("refusing to check out: repo '${refs.repo}' is not a plain GitHub repository name")
    }
    if (!(refs.base_sha?.trim() ==~ shaPattern)) {
        error("refusing to check out: base sha '${refs.base_sha}' is not a git object name")
    }
    if (!(refs.base_ref?.trim() ==~ refPattern)) {
        error("refusing to check out: base ref '${refs.base_ref}' is not a plain branch name")
    }
    refs.pulls.each { pull ->
        if (!(pull.sha?.trim() ==~ shaPattern)) {
            error("refusing to merge pull request #${pull.number}: '${pull.sha}' is not a git object name")
        }
    }

    final remoteRefSpec = (
        ["+refs/heads/${refs.base_ref}:refs/remotes/origin/${refs.base_ref}"] + (
            (refs.pulls && refs.pulls.size() > 0) ? refs.pulls.collect {
                "+refs/pull/${it.number}/head:refs/remotes/origin/pr/${it.number}/head"
            }: []
        )
    ).join(' ')

    final pullsSHAs = refs.pulls.collect { it.sha }.join(' ')
    sh label: 'Checkout and merge pull request(s) to target if exist', script: """#!/usr/bin/env bash
        set -e
        git --version
        git init
        git rev-parse --resolve-git-dir .git

        git config --global user.email "ti-chi-bot@ci" && git config --global user.name "TiChiBot"
        git config remote.origin.url "${remoteUrl}"
        git config core.sparsecheckout true

        # reset & clean (worktree only)
        git reset --hard
        git clean -ffdx

        # Prune stale PR refs from previous cached runs.
        # These refs keep old PR commits reachable and make the cached .git grow run by run.
        git for-each-ref --format='%(refname)' 'refs/remotes/origin/pr/' | xargs -r -n 1 -I {} git update-ref -d "{}" || true

        # fetch pull requests and target branch.
        # remoteRefSpec and pullsSHAs are whitespace-separated lists and are
        # word-split on purpose; every entry was validated as a ref/object name.
        timeout "${timeout}m" git fetch --force --verbose --prune --prune-tags -- "${remoteUrl}" ${remoteRefSpec}

        # checkout to refs.base_sha
        git checkout -f "origin/${refs.base_ref}"

        echo "🚧 Checkouting to base SHA:${refs.base_sha}..."
        git checkout "${refs.base_sha}"
        echo "✅ Checked. 🎉"

        echo "🧾 HEAD info:"
        git rev-parse HEAD^{commit}
        git log -n 3 --oneline

        # merge pull requests to base if exist.
        if [ -n "${pullsSHAs}" ]; then
            echo "🚧 Pre-merge heads of pull requests to base SHA: ${refs.base_sha} ..."
            git merge --no-edit ${pullsSHAs}

            echo "🧾 Pre-merged result:"
            git rev-parse HEAD^{commit}
            git log -n 3 --oneline

            echo "✅ Pre merged 🎉"
        fi

        git clean -ffdx
        if [ "${withSubmodule}" == "true" ]; then
            echo "📁 update submodules ..."
            # Submodule URLs come from the pull request's tree, and the fetch runs
            # with the bot's SSH credential. Verify the host key instead of
            # disabling the check: seed it once, then require a match, so a
            # rewritten URL cannot be served by an impostor.
            # The CDN path skips the seeding above, but submodules still resolve
            # to github.com, so seed the published keys here as well.
            mkdir -p ~/.ssh && chmod 0700 ~/.ssh
            {
                curl -fsSL --retry 3 --retry-delay 2 --connect-timeout 5 --max-time 30 \
                    https://api.github.com/meta \
                    | tr ',' '\\n' \
                    | grep -oE '"(ssh-[a-z0-9]|ecdsa-sha2)[^"]*"' \
                    | tr -d '"' \
                    | sed 's/^/github.com /'
            } >> ~/.ssh/known_hosts || true
            GIT_SSH_COMMAND="ssh -o StrictHostKeyChecking=yes" git submodule update --init --recursive
            echo "✅ update submodules done"
        fi

        # Git maintenance to keep cached workspaces bounded in size.
        # Also run gc in submodules if present (workspace cache includes them).
        (
            git reflog expire --expire=now --all || true
            git gc --prune=now || true
            if [ -f .gitmodules ]; then
                git submodule foreach --recursive '
                    git reflog expire --expire=now --all || true
                    git gc --prune=now || true
                ' || true
            fi
        ) >/dev/null 2>&1 || true

        # Jenkins `stash` archives only files, so an empty directory is dropped.
        # `git gc` packs all refs and leaves the refs directory empty; without
        # any file in it, a workspace restored via `unstash` is not recognized
        # as a valid git repository, and git commands fall back to an ancestor
        # repository (e.g. the CI checkout at the workspace root). Keep a
        # sentinel so a transferred git directory stays valid. Resolve the real
        # git dir because `.git` is a file for submodule/worktree checkouts
        # (e.g. tidb/pkg/extension/enterprise).
        refs_dir="\$(git rev-parse --git-path refs)"
        mkdir -p "\$refs_dir"
        touch "\$refs_dir/.keep"

        echo "✅ ~~~~~All done.~~~~~~"
    """
}

// Unique cache save key by refs.
//
// The key encodes the build kind, the target branch and the shas, so a pull
// request never shares a key with the branch it targets or with another pull
// request.
def getCacheKey(prefixFolder, refs, part = '') {
    final prefix = ([prefixFolder, refs.org, refs.repo, part] - '').join('/')
    final baseSha = refs.base_sha[0..<7]
    if (refs.pulls && refs.pulls.size() > 0) {
        // pr-<base ref>-<base sha>-<p1>_<p2>_...<pN>
        final pulls = refs.pulls.collect { it.sha[0..<7] }.join('_')
        return "${prefix}/pr-${refs.base_ref}-${baseSha}-${pulls}".toString()
    }
    return "${prefix}/branch-${refs.base_ref}-${baseSha}".toString()
}

// Restore only from branch builds of the same target branch.
//
// The workspace cache holds `**/*`, including `.git`, so restoring a cache
// written by a different pull request would hand that pull request's workspace
// (and its git state) to this build. Branch builds are the reviewed, merged
// content, so they are the only restore source.
def getRestoreKeys(prefixFolder, refs, part = '') {
    final prefix = ([prefixFolder, refs.org, refs.repo, part] - '').join('/')
    return ["${prefix}/branch-${refs.base_ref}-".toString()]
}

/**
 * Run the body only when the cache identified by `key` is missing.
 *
 * The `cache` step always executes its body (restore -> body -> save), so an
 * exact-key hit would still rerun the body. This wrapper stores a marker file
 * inside the cached path and skips the body when the marker for the same key is
 * restored, so expensive steps can be skipped on a retest.
 *
 * Args:
 *   - path        (required) directory to cache, absolute or relative to the current dir
 *   - key         (required) exact cache key
 *   - restoreKeys (optional) fallback keys; a fallback restore still runs the body
 *   - includes    (optional) Ant-style include pattern inside path, default '**\/*'
 *   - name        (optional) label used in log messages, default the key
 *   - marker      (optional) marker file name inside path, default '.cache-complete'
 */
def withCache(Map args, Closure body) {
    final path = args.path
    final key = args.key
    final name = args.name ?: key
    final marker = args.marker ?: '.cache-complete'
    final markerFile = path + '/' + marker
    final includes = [args.includes ?: '**/*', marker].join(',')

    cache(path: path, key: key, includes: includes, restoreKeys: args.restoreKeys ?: []) {
        if (fileExists(file: markerFile) && readFile(file: markerFile).trim() == key) {
            echo "[cache] hit '${name}' (key=${key}), skip the body"
            return
        }
        echo "[cache] miss '${name}' (key=${key}), run the body"
        body()
        writeFile(file: markerFile, text: key)
    }
}

def uploadCoverageToCodecov(refs, flags = "", file = "", credentialsId = 'codecov-token-tidb') {
    // The token is bound only for the upload step. Pipelines used to put it in
    // the `environment` block of the stage that runs the pull request's own
    // build/test scripts, so any contributor could read it (and Codecov upload
    // tokens allow uploading coverage, and therefore appear as the repository).

    // Skip for batch build.
    if (refs.pulls && refs.pulls.size() > 1) {
        return
    }

    final codecovGitOptions = (refs.pulls ?
         "--branch origin/pr/${refs.pulls[0].number} --sha ${refs.pulls[0].sha} --pr ${refs.pulls[0].number}" :
         "--branch origin/${refs.base_ref} --sha ${refs.base_sha}"
    )

    withCredentials([string(credentialsId: credentialsId, variable: 'CODECOV_TOKEN')]) {
        sh label: "upload coverage to codecov", script: """#!/usr/bin/env bash
        coverageFile=${file}
        if [ -f \$coverageFile ]; then
            # Prefer the codecov binary bundled in the CI base image; download from
            # the public uploader.codecov.io only when it is not available.
            if command -v codecov >/dev/null 2>&1; then
                codecov_bin="codecov"
            else
                echo "INFO: codecov not found in image; downloading from uploader.codecov.io."
                curl -fsSL --retry 3 --retry-delay 2 --connect-timeout 5 --max-time 120 \
                    -o codecov "https://uploader.codecov.io/v0.8.0/linux/codecov"
                # The uploader is executed with the coverage token in its environment
                # and with the workspace in reach, so pin it to the digest the release
                # publishes instead of trusting whatever the URL returned.
                echo "b37359013b48fbc3b0790d59fc474a52a260fb96e28e1b2c2ae001dc9b9cc996  codecov" | sha256sum -c - || {
                    echo "ERROR: codecov uploader digest mismatch; refusing to execute it"
                    exit 1
                }
                chmod +x codecov
                codecov_bin="./codecov"
            fi
            \${codecov_bin} --rootDir . --flags ${flags} --file \${coverageFile} ${codecovGitOptions}
            fi
        """
    }
}

// send test case run report to cloudevents server
def sendTestCaseRunReport(repo, branch, dataFile = 'bazel-go-test-problem-cases.json') {
    sh label: 'Send event to cloudevents server', script: """timeout 10 \
        curl --verbose --request POST --url http://cloudevents-server.cs.svc/events \
        --header "ce-id: \$(uuidgen)" \
        --header "ce-source: \${JENKINS_URL}" \
        --header 'ce-type: test-case-run-report' \
        --header 'ce-repo: ${repo}' \
        --header 'ce-branch: ${branch}' \
        --header "ce-buildurl: \${BUILD_URL}" \
        --header 'ce-specversion: 1.0' \
        --header 'content-type: application/json; charset=UTF-8' \
        --data @${dataFile} || true
    """
}

// print PR info on pipeline run description.
def setPRDescription(refs) {
    try {
        if (refs.pulls && refs.pulls.size() > 0) {
            currentBuild.description = "PR #${refs.pulls[0].number}: ${refs.pulls[0].title} ${refs.pulls[0].link}"
        } else {
            println("No pull request information available.")
        }
    } catch (Exception e) {
        println("Failed to set PR description: ${e.message}. Please ignore it. 🤷")
    }
}
