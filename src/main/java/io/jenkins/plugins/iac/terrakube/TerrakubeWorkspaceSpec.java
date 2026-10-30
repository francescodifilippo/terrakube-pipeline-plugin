package io.jenkins.plugins.iac.terrakube;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jenkins.plugins.iac.core.HttpJsonClient;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Validated description of the workspace a {@code terrakubeWorkspace} step finds or creates, persisted with the step
 * execution. It holds no secret: sensitive variables are Jenkins credential IDs, read when the variable is created.
 */
final class TerrakubeWorkspaceSpec implements Serializable {
 private static final long serialVersionUID=1L;
 /** Source and branch Terrakube's UI gives a CLI-driven workspace, whose configuration is uploaded instead. */
 static final String CLI_SOURCE="empty",CLI_BRANCH="remote-content";
 final String organizationName,workspaceName,description,terraformVersion,iacType,executionMode;
 final String source,branchName,workingDirectory,vcsId;
 /** Plain values by name. */
 final LinkedHashMap<String,String> variables,environmentVariables;
 /** Jenkins Secret text credential IDs by variable name. */
 final LinkedHashMap<String,String> sensitiveVariables,sensitiveEnvironmentVariables;

 TerrakubeWorkspaceSpec(String organizationName,String workspaceName,String description,String terraformVersion,String iacType,
   String executionMode,String source,String branchName,String workingDirectory,String vcsId,
   Map<String,String> variables,Map<String,String> environmentVariables,
   Map<String,String> sensitiveVariables,Map<String,String> sensitiveEnvironmentVariables){
  this.organizationName=organizationName;this.workspaceName=workspaceName;this.description=description;
  this.terraformVersion=terraformVersion;this.iacType=iacType;this.executionMode=executionMode;
  this.source=source;this.branchName=branchName;this.workingDirectory=workingDirectory;this.vcsId=vcsId;
  this.variables=new LinkedHashMap<>(variables);this.environmentVariables=new LinkedHashMap<>(environmentVariables);
  this.sensitiveVariables=new LinkedHashMap<>(sensitiveVariables);
  this.sensitiveEnvironmentVariables=new LinkedHashMap<>(sensitiveEnvironmentVariables);
 }
 /** Workspace attributes; unset options are left out so that Terrakube applies its defaults. */
 ObjectNode attributes(){
  ObjectNode a=HttpJsonClient.JSON.createObjectNode().put("name",workspaceName).put("terraformVersion",terraformVersion);
  a.put("source",source==null?CLI_SOURCE:source).put("branch",source==null?CLI_BRANCH:branchName);
  // Terrakube's UI default; the executor also reads a missing folder as "/".
  a.put("folder",workingDirectory==null?"/":workingDirectory);
  if(description!=null)a.put("description",description);
  if(iacType!=null)a.put("iacType",iacType);
  if(executionMode!=null)a.put("executionMode",executionMode);
  return a;
 }
 ObjectNode relationships(){
  ObjectNode r=HttpJsonClient.JSON.createObjectNode();
  if(vcsId!=null)r.putObject("vcs").putObject("data").put("type","vcs").put("id",vcsId);
  return r;
 }
 boolean hasVariables(){
  return !variables.isEmpty()||!environmentVariables.isEmpty()||!sensitiveVariables.isEmpty()||!sensitiveEnvironmentVariables.isEmpty();
 }
}
