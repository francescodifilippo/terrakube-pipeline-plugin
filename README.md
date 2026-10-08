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

Waits for a previously submitted Terrakube operation using its `operationKey`.

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

Set `waitForCompletion: false` to submit the job and continue Jenkins execution. A later `terrakubeAwait` can resume waiting without exposing the provider job ID in Pipeline source.

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
