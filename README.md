# Terrakube Pipeline

**Proposed Jenkins plugin ID:** `terrakube-pipeline`  
**Proposed GitHub repository:** `terrakube-pipeline-plugin`  
**Version:** `0.1.0-SNAPSHOT` (source prototype)

Declarative Jenkins Pipeline integration with **Terrakube**. The provider-specific stage options are `terrakubeProvision` for submission and `terrakubeAwait` for optional deferred waiting, including the `waitForCompletion` flag. See `examples/Jenkinsfile.terrakube` for a prototype Jenkinsfile. Connection and credentials configuration is managed through Jenkins.

Requires the separate **`iac-pipeline-api`** Jenkins plugin. The public Terrakube DSL keeps `server`, `organizationId`, `workspaceId` and `templateId`; internally only the provider-neutral `connectionId`, `targetId` and selected non-secret operation metadata are persisted by the core.

## Local build

Install the sibling core SNAPSHOT before building this provider:

```bash
(cd ../iac-pipeline-api-plugin && mvn -B -ntp install)
mvn -B -ntp verify
```

For pull requests, GitHub Actions looks for the same branch name in `iac-pipeline-api-plugin`; if no matching core branch exists it falls back to `main`.

## Example

The Declarative stage options accept `organizationId`, `workspaceId`, `templateId`, optional `branch`, and support background submission via `waitForCompletion: false` followed by `terrakubeAwait` in a later stage.

## Release order and caveats

Release `iac-pipeline-api` first and replace the core SNAPSHOT dependency with its tested release version before publishing this provider. Validate the Declarative syntax with JenkinsRule, controller restart/abort cases and the provider API before shipping an HPI. **The source ZIP is not an installable plugin.**
