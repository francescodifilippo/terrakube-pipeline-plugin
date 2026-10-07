package io.jenkins.plugins.iac.terrakube;
import hudson.Extension;
import hudson.model.Run;
import io.jenkins.plugins.iac.core.*;
import java.util.Map;
@Extension public final class TerrakubeBackend implements IacBackend {
 @Override public String id(){return "terrakube";}
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
