// compose prow's refs struct from gitee event payload json.
def composeRefFromEventPayload(String jsonEventBody) {
    final body = readJSON(text: jsonEventBody)
    return [
        org: body.repository.namespace,
        repo: body.repository.path,
        repo_link: body.repository.url,
        base_ref: body.pull_request.base.ref,
        base_sha: body.pull_request.base.sha,
        pulls: [
            [
                author: body.pull_request.head.ref,
                number: body.pull_request.number,
                sha: body.pull_request.head.sha,
                title: body.pull_request.title,
            ]
        ]
    ]
}


hello???
