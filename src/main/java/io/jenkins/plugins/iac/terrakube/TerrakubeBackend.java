package io.jenkins.plugins.iac.terrakube;
import hudson.Extension;
import hudson.model.Run;
import io.jenkins.plugins.iac.core.*;
import java.util.Map;
@Extension public final class TerrakubeBackend implements IacBackend {
 @Override public String id(){return "terrakube";}
 @Override public TerrakubeConnections connections(){return TerrakubeConnections.get();}
 @Override public JobResult submit(String server,String workspaceId,Map<String,String> opts,Run<?,?> run) throws Exception {
  try(HttpJsonClient c=connections().client(server,run)){
    return new TerrakubeApi(c).submit(opts.get("organizationId"),workspaceId,opts.get("templateId"),opts.get("branch"));
  }
 }
 @Override public JobResult status(String server,String org,String remoteId,Run<?,?> run) throws Exception {
  try(HttpJsonClient c=connections().client(server,run)){return new TerrakubeApi(c).status(org,remoteId);}
 }
}
