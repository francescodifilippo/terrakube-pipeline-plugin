# Terrakube Pipeline

**Plugin ID:** `terrakube-pipeline`  
**Status:** pre-release

Declarative Jenkins Pipeline integration for Terrakube. It submits Terrakube jobs through the shared [IaC Pipeline API](https://github.com/francescodifilippo/iac-pipeline-api-plugin), persists build-local remote execution state, and supports synchronous or deferred waiting. It can also create the workspaces those jobs run on.

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

### `terrakubeWorkspace`

Finds a workspace by name, or creates it with its variables, then runs the stage. It runs no job. See [Workspace creation](#workspace-creation).

| Parameter | Purpose |
| --- | --- |
| `connection` | configured Terrakube connection name |
| `organizationName` | exact name of the Terrakube organization |
| `workspaceName` | exact workspace name, up to 128 characters |
| `terraformVersion` | Terraform (or OpenTofu) version the workspace runs, for example `1.9.5`; required |
| `source` | optional repository URL; without it the workspace is CLI-driven |
| `branchName` | branch of `source`; required with `source` |
| `workingDirectory` | optional directory of the configuration inside the repository, for example `/stacks/network` (Terrakube's `folder`); `/` otherwise |
| `vcsId` | optional ID of a Terrakube VCS connection of the organization, for private repositories |
| `iacType` | optional `terraform` or `tofu`; Terrakube's default (`terraform`) otherwise |
| `executionMode` | optional `remote` or `local`; the organization's default otherwise |
| `description` | optional workspace description |
| `variables` | optional map of plain Terraform variables |
| `environmentVariables` | optional map of plain environment variables |
| `sensitiveVariables` | optional map from Terraform variable name to the ID of a Jenkins Secret text credential |
| `sensitiveEnvironmentVariables` | optional map from environment variable name to the ID of a Jenkins Secret text credential |
| `ifExists` | `keep` (default) or `fail` when the workspace already exists |

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

## Workspace creation

`terrakubeWorkspace` creates a workspace in a stage of its own, separate from the stages that run jobs:

```groovy
stage('Workspace') {
  options {
    terrakubeWorkspace(
      connection: 'terrakube-prod',
      organizationName: 'acme',
      workspaceName: 'network',
      terraformVersion: '1.9.5',
      source: 'https://github.com/acme/infrastructure.git',
      branchName: 'main',
      variables: [region: 'eu-west-1', subnet_count: 3],
      sensitiveVariables: [db_password: 'network-db-password']
    )
  }
  steps { echo 'Workspace is ready' }
}
```

A later stage runs jobs on it with `terrakubeProvision(organizationName: 'acme', workspaceName: 'network', templateName: ...)`, as described in [Names](#names). See [examples/Jenkinsfile.terrakube-create-workspace](examples/Jenkinsfile.terrakube-create-workspace).

### Existing workspaces

When the stage runs, the plugin looks the organization up by name, then lists its workspaces named exactly `workspaceName`, as `terrakubeProvision` does. Deleted workspaces are ignored.

- No workspace: the plugin creates it, then creates its variables.
- One workspace: with `ifExists: 'keep'` (default) the stage uses it. Its settings and variables are not compared or changed. With `ifExists: 'fail'` the stage fails.
- More than one: the stage fails and lists their IDs.

Terrakube rejects a second workspace with the same name in an organization (HTTP 409), and the plugin then treats the workspace as existing. Terrakube checks this in the application, not in the database, so two creates at the same moment can both succeed. After creating a workspace the plugin lists the name again and fails the stage, before creating any variable, if it finds another workspace. Prevent this with `disableConcurrentBuilds()` or a `lock` around the stage.

### Source

- With `source` and `branchName`, jobs read the configuration from that repository. For a private repository, set `vcsId` to an existing VCS connection of the organization; the plugin does not create one.
- Without `source`, the workspace is CLI-driven, as Terrakube's UI creates it (`source` `empty`, `branch` `remote-content`): its configuration must be uploaded, for example with the Terraform CLI, before a job can run.

### Variables

- `variables` and `environmentVariables` hold plain values. Numbers and booleans are turned into strings. They are visible in Terrakube to anyone who can read the workspace variables.
- `sensitiveVariables` and `sensitiveEnvironmentVariables` name a Jenkins **Secret text** credential for each variable. The plugin reads the credential when it creates the variable and sends the value to Terrakube as a sensitive variable, whose value the API never returns. The value is not stored in the build and never written to the log.
- A name can be in either the plain or the sensitive map of its category, not both.
- All values are sent as strings (`hcl: false`).

The plugin checks that every credential exists before it creates the workspace. The build log shows the variable names, never their values.

If a variable cannot be created, the stage fails and names the workspace. The workspace then exists with only some of its variables, and a later run of the stage keeps it as it is: set the missing variables in Terrakube, or delete the workspace before running the stage again.

### Restarts

The step holds no executor. It saves that it sent the create request before sending it. If Jenkins restarts afterwards, the resumed step finds the workspace, treats it as its own, and creates only the variables that are still missing.

### Permissions

Creating the workspace and its variables needs a team with the organization permission **Manage Workspaces**. Terrakube's project-limited create permission does not apply, because the step does not assign a project.

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

See [examples/Jenkinsfile.terrakube](examples/Jenkinsfile.terrakube), and [examples/Jenkinsfile.terrakube-by-name](examples/Jenkinsfile.terrakube-by-name) for a stage that uses names. [examples/Jenkinsfile.terrakube-create-workspace](examples/Jenkinsfile.terrakube-create-workspace) creates the workspace first.

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

`terrakubeWorkspace` needs the **Manage Workspaces** permission (see [Permissions](#permissions)); use a separate connection and token for pipelines that create workspaces. Put secrets only in `sensitiveVariables` or `sensitiveEnvironmentVariables`, never in the plain maps.

## Compatibility

The public DSL uses `connection`, like the other IaC provider plugins, and retains `organizationId`, `workspaceId`, and `templateId`, with `organizationName`, `workspaceName`, and `templateName` as optional alternatives; the shared core remains provider-neutral. The IDs are now optional in Pipeline Syntax, since a name can replace each of them.

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
