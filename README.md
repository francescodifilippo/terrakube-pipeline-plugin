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
| `server` | configured Terrakube connection |
| `organizationId` | Terrakube organization |
| `workspaceId` | Terrakube workspace |
| `templateId` | Terrakube template |
| `branch` | optional source branch |
| `operationKey` | build-local correlation key |
| `waitForCompletion` | wait now or continue after submission |
| `pollingSeconds` | polling interval |
| `timeoutMinutes` | maximum Jenkins-side wait |

### `terrakubeAwait`

Uses `server` and a previously submitted `operationKey` to resume waiting later in the same build. The provision stage it refers to must set that `operationKey` explicitly.

## Operation keys

- Without `operationKey`, the default key is derived from the provider, `workspaceId`, and the step parameters (`organizationId`, `templateId`, `branch`), so two stages running different templates on the same workspace both submit. Two stages with identical parameters on the same workspace share a default key, so the second one fails: give them distinct `operationKey`s. Default keys are internal: do not reference them from `terrakubeAwait`.
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

## Example

```groovy
stage('Terrakube provisioning') {
  options {
    terrakubeProvision(
      server: 'tk-prod',
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

See [examples/Jenkinsfile.terrakube](examples/Jenkinsfile.terrakube).

## Asynchronous execution

Set `waitForCompletion: false` to submit the job and continue Jenkins execution. A later `terrakubeAwait` with the same explicit `operationKey` can resume waiting without exposing the provider job ID in Pipeline source.

```groovy
stage('Submit') {
  options {
    terrakubeProvision(server: 'tk-prod', organizationId: 'org-prod', workspaceId: 'ws-prod',
      templateId: 'tpl-network', operationKey: 'network-job', waitForCompletion: false)
  }
  steps {
    echo 'Terrakube job submitted'
  }
}
stage('Await') {
  options {
    terrakubeAwait(server: 'tk-prod', operationKey: 'network-job')
  }
  steps {
    echo 'Terrakube job completed'
  }
}
```

## Restart and durability

Terrakube-specific `organizationId` is stored only as non-secret provider metadata required to query the remote job. After the remote ID is persisted, polling survives controller restart. Ambiguous submission before a remote ID is saved fails closed because the provider does not currently advertise idempotent submit support.

## Security

Keep API tokens in Jenkins Credentials and use HTTPS for production endpoints. See [SECURITY.md](SECURITY.md).

## Compatibility

The public DSL retains `server`, `organizationId`, `workspaceId`, and `templateId`; the shared core remains provider-neutral.

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
