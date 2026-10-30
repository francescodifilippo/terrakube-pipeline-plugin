# Terrakube Pipeline

**Plugin ID:** `terrakube-pipeline`  
**Status:** pre-release

Declarative Jenkins Pipeline integration for Terrakube. It submits Terrakube jobs through the shared [IaC Pipeline API](https://github.com/francescodifilippo/iac-pipeline-api-plugin), persists build-local remote execution state, and supports synchronous or deferred waiting.

## Requirements

- Jenkins 2.568.3 or newer
- Java 21
- `iac-pipeline-api` of the matching tested version
- a Terrakube API endpoint and Jenkins credential

## Installation

For local pre-release testing:

```bash
(cd ../iac-pipeline-api-plugin && mvn -B -ntp install)
mvn -B -ntp verify
```

The generated HPI is under `target/`.

## Configuration

In **Manage Jenkins → System → Terrakube connections**, configure the API connection and Jenkins credential. Credentials are resolved when a job is submitted or polled and are not stored in the build's `RemoteOperation`.

## Pipeline syntax

### `terrakubeProvision`

| Parameter | Purpose |
| --- | --- |
| `connection` | configured Terrakube connection |
| `organizationId` | Terrakube organization ID; set either `organizationId` or `organizationName` |
| `organizationName` | exact organization name, looked up when the stage runs (see [Names](#names)) |
| `workspaceId` | Terrakube workspace ID; set either `workspaceId` or `workspaceName` |
| `workspaceName` | exact workspace name in the organization, looked up when the stage runs |
| `templateId` | Terrakube template ID; set either `templateId` or `templateName` |
| `templateName` | exact template name in the organization, looked up when the stage runs |
| `branch` | optional source branch |
| `operationKey` | build-local correlation key |
| `waitForCompletion` | wait now or continue after submission |
| `pollingSeconds` | polling interval |
| `timeoutMinutes` | maximum Jenkins-side wait |

### `terrakubeAwait`

Uses `connection` and a previously submitted `operationKey` to resume waiting later in the same build. The provision stage it refers to must set that `operationKey` explicitly.

## Names

A stage can name its organization, workspace and template instead of giving their IDs, in any combination:

```groovy
stage('Terrakube provisioning') {
  options {
    terrakubeProvision(
      connection: 'tk-prod',
      organizationName: 'acme',
      workspaceName: 'network',
      templateName: 'Plan and apply',
      operationKey: 'network-job'
    )
  }
  steps { echo 'Terrakube job completed' }
}
```

- For each of organization, workspace and template, set exactly one of the ID or the name, for example `organizationId: 'org-prod', workspaceName: 'network', templateId: 'tpl-network'`. These checks run when the stage starts, before any Terrakube call, and fail the stage without running its body.
- Names are matched exactly and case-sensitively, including spaces. They can contain any characters except control characters, up to 255 characters.
- When the stage runs, the plugin first looks the organization up by name if needed, then the workspace and the template inside that organization, with the stage's connection. It reads the Terrakube JSON:API collections with an RSQL name filter:
  - `GET /api/v1/organization?filter[organization]=name=='acme'`
  - `GET /api/v1/organization/{organizationId}/workspace?filter[workspace]=name=='network';deleted==false`
  - `GET /api/v1/organization/{organizationId}/template?filter[template]=name=='Plan and apply'`
- The filter only narrows the list. Terrakube treats a leading or trailing `*` as a wildcard, and depending on the database a name may match without regard to case, so the plugin compares the returned names itself. Deleted workspaces never match; Terrakube hides them, and so do disabled organizations.
- Exactly one match is required. No match fails the stage with, for example, `No Terrakube workspace named 'network' in organization 'acme'; check workspaceName or use workspaceId`. Several matches fail it with their count and IDs. Other errors, such as HTTP 403 for a token that cannot read the organization, fail the stage with their HTTP status. Lookup failures are not retried.
- Terrakube does not allow these lookups to request pages, so it returns at most 500 organizations or templates, or 10000 workspaces, per filter. If a response is that large, the plugin cannot know that it saw every match, and fails instead of guessing. This only matters for names starting or ending with `*`; use the ID instead.
- The build log shows what was resolved, for example `IaC terrakube: resolved target to <workspace ID>` and `IaC terrakube: resolved organizationId to <organization ID>`.
- Everything after the lookup uses the IDs: the stored operation, the job, and status polling. The lookup runs once per `terrakubeProvision` stage. `terrakubeAwait` refers to an operation by `operationKey` and never looks up names.
- A stage that gives all three IDs makes no lookup call and behaves as before.

The token used by the connection must be able to read what the stage names, as checked by Terrakube's read permissions:

| Lookup | Terrakube read permission |
| --- | --- |
| `organizationName` | membership of a team of the organization, or limited or project access to it |
| `workspaceName` | a team of the organization (for workspaces without a project, or teams that manage workspaces), access to the workspace's project, or any access grant on the workspace |
| `templateName` | membership of a team of the organization, or limited access to it |

Terrakube superusers can read all of them.

See [examples/Jenkinsfile.terrakube-by-name](examples/Jenkinsfile.terrakube-by-name).

## Operation keys

- Without `operationKey`, the default key is derived from the provider, `workspaceId`, and the step parameters (`organizationId`, `templateId`, `branch`, and any names given instead of IDs), so two stages running different templates on the same workspace both submit. Two stages with identical parameters on the same workspace share a default key, so the second one fails: give them distinct `operationKey`s. Default keys are internal: do not reference them from `terrakubeAwait`.
- Reusing an `operationKey` in another `terrakubeProvision` stage of the same build fails that stage instead of adopting the earlier job. This includes a provision stage re-run in the same build, for example inside `retry`: give each attempt its own key.

## Job status handling

The plugin maps Terrakube job statuses (`JobStatus`, matched case-insensitively) as follows:

| Terrakube status | Result |
| --- | --- |
| `completed`, `noChanges` | success; the stage body runs |
| `failed`, `rejected`, `cancelled` | the stage fails |
| `waitingApproval` | approval pending: the log reports it and polling continues until the job is approved or rejected in the Terrakube UI, or `timeoutMinutes` elapses |
| any other status (`pending`, `approved`, `queue`, `running`, `unknown`, ...) | in progress; polling continues |

- Whether a job stops for approval depends on its Terrakube template. The plugin does not approve or reject jobs itself.
- Rejecting the job in Terrakube fails the stage.

Transient status poll errors (I/O errors, HTTP 5xx, 408, 429) are retried as described in the IaC Pipeline API; other HTTP 4xx responses fail the stage.

## Behavior changes

- `noChanges` now counts as success and `rejected` as failure; both previously kept polling until `timeoutMinutes`.
- `waitingApproval` is now reported in the build log as approval pending.
- The default `operationKey` now includes the step parameters, and reusing an `operationKey` in the same build fails the stage.
- `terrakubeAwait` requires the explicit `operationKey` of an earlier provision stage.
- Transient status poll errors are retried instead of failing the stage at once.
- `organizationId`, `workspaceId`, and `templateId` are optional in Pipeline Syntax, since a name can replace each of them. A stage that sets neither the ID nor the name is no longer rejected when the Pipeline is parsed: earlier stages run, and that stage fails when it starts, for example with `terrakubeProvision needs organizationId or organizationName in the stage options`.
- The step parameter `server` was renamed to `connection` after `0.1.0-beta-1`, so that all IaC provider plugins use the same name. Pipelines written for that pre-release must replace `server:` with `connection:`. The saved connection list was renamed too, so connections configured with `0.1.0-beta-1` must be entered again.

## Example

```groovy
stage('Terrakube provisioning') {
  options {
    terrakubeProvision(
      connection: 'tk-prod',
      organizationId: 'org-prod',
      workspaceId: 'ws-prod',
      templateId: 'tpl-network',
      branch: 'main',
      operationKey: 'network-job',
      waitForCompletion: true,
      timeoutMinutes: 90
    )
  }
  steps { echo 'Terrakube job completed' }
}
```

See [examples/Jenkinsfile.terrakube](examples/Jenkinsfile.terrakube), and [examples/Jenkinsfile.terrakube-by-name](examples/Jenkinsfile.terrakube-by-name) for a stage that uses names.

## Asynchronous execution

Set `waitForCompletion: false` to submit the job and continue Jenkins execution. A later `terrakubeAwait` with the same explicit `operationKey` can resume waiting without exposing the provider job ID in Pipeline source.

```groovy
stage('Submit') {
  options {
    terrakubeProvision(connection: 'tk-prod', organizationId: 'org-prod', workspaceId: 'ws-prod',
      templateId: 'tpl-network', operationKey: 'network-job', waitForCompletion: false)
  }
  steps {
    echo 'Terrakube job submitted'
  }
}
stage('Await') {
  options {
    terrakubeAwait(connection: 'tk-prod', operationKey: 'network-job')
  }
  steps {
    echo 'Terrakube job completed'
  }
}
```

## Restart and durability

Terrakube-specific `organizationId` (resolved from `organizationName` when a name is used) is stored only as non-secret provider metadata required to query the remote job. After the remote ID is persisted, polling survives controller restart. Ambiguous submission before a remote ID is saved fails closed because the provider does not currently advertise idempotent submit support.

## Security

Keep API tokens in Jenkins Credentials and use HTTPS for production endpoints. See [SECURITY.md](SECURITY.md).

## Compatibility

The public DSL uses `connection`, like the other IaC provider plugins, and retains `organizationId`, `workspaceId`, and `templateId`, with `organizationName`, `workspaceName`, and `templateName` as optional alternatives; the shared core remains provider-neutral. The IDs are now optional in Pipeline Syntax, since a name can replace each of them.

## Possible future features

These ideas are not planned and may never be implemented. They are listed so that the current scope is clear.

- **Workspace creation.** The plugin works only with organizations, workspaces and templates that already exist in Terrakube. A future step could create a workspace, or update its settings such as the VCS repository and variables, and reuse an existing workspace instead of creating a duplicate. Creating and configuring workspaces is a different concern from running jobs on them, so this does not seem to belong in this plugin at the moment.

## Development

```bash
(cd ../iac-pipeline-api-plugin && mvn -B -ntp install)
mvn -B -ntp verify
```

GitHub Actions uses a same-named core branch when present and otherwise falls back to core `main`.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

MIT License. See [LICENSE](LICENSE).
