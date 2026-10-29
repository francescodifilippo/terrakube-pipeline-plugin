package io.jenkins.plugins.iac.terrakube;
import hudson.Extension;
import hudson.model.Run;
import io.jenkins.plugins.iac.core.*;
import java.util.LinkedHashMap;
import java.util.Map;
@Extension public final class TerrakubeBackend implements IacBackend {
 @Override public String id(){return "terrakube";}
 /**
  * IDs are used as given; with no names there is no Terrakube call. Names are looked up read-only: the organization
  * first, then the workspace and template inside it. The resolved organizationId and templateId are added to the
  * parameters, which keep the names.
  */
 @Override public ResolvedTarget resolve(String connectionId,String targetId,Map<String,String> parameters,Run<?,?> run) throws Exception {
  String organizationName=parameters.get("organizationName"),workspaceName=parameters.get("workspaceName"),
    templateName=parameters.get("templateName");
  if(targetId==null&&workspaceName==null)throw new IllegalArgumentException("terrakubeProvision needs workspaceId or workspaceName");
  if(organizationName==null&&workspaceName==null&&templateName==null)return new ResolvedTarget(targetId,parameters);
  try(HttpJsonClient c=TerrakubeConnections.get().client(connectionId,run)){
   TerrakubeApi api=new TerrakubeApi(c);
   String organizationId=organizationName!=null?api.organizationIdByName(organizationName):
     Identifiers.required(parameters.get("organizationId"),"organizationId");
   String organization=organizationName!=null?"'"+organizationName+"'":organizationId;
   String workspaceId=workspaceName!=null?api.workspaceIdByName(organizationId,organization,workspaceName):targetId;
   String templateId=templateName!=null?api.templateIdByName(organizationId,organization,templateName):
     Identifiers.required(parameters.get("templateId"),"templateId");
   Map<String,String> resolved=new LinkedHashMap<>(parameters);
   resolved.put("organizationId",organizationId);resolved.put("templateId",templateId);
   return new ResolvedTarget(workspaceId,resolved);
  }
 }
 @Override public Map<String,String> operationMetadata(SubmissionRequest request){
  String organizationId=request.parameters().get("organizationId");
  if(organizationId==null || organizationId.isBlank())
   throw new IllegalArgumentException("organizationId is required");
  return Map.of("organizationId",organizationId);
 }
 @Override public JobResult submit(SubmissionRequest request,Run<?,?> run) throws Exception {
  Map<String,String> opts=request.parameters();
  try(HttpJsonClient c=TerrakubeConnections.get().client(request.connectionId(),run)){
    return new TerrakubeApi(c).submit(opts.get("organizationId"),request.targetId(),opts.get("templateId"),opts.get("branch"));
  }
 }
 @Override public JobResult status(RemoteOperation operation,Run<?,?> run) throws Exception {
  String organizationId=operation.metadata().get("organizationId");
  if(organizationId==null || organizationId.isBlank())
   throw new IllegalStateException("Terrakube operation is missing organizationId metadata");
  try(HttpJsonClient c=TerrakubeConnections.get().client(operation.connectionId(),run)){
   return new TerrakubeApi(c).status(organizationId,operation.remoteId());
  }
 }
}
