package io.jenkins.plugins.iac.terrakube;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jenkins.plugins.iac.core.*;
import java.io.IOException;
import java.util.Set;
/** Jobs are driven by Terrakube's own templates, not by OTF plan/apply flags. */
public final class TerrakubeApi {
 private final HttpJsonClient transport;
 private static final Set<String> SUCCESS=Set.of("completed");
 private static final Set<String> FAILED=Set.of("failed","error","canceled","cancelled");
 TerrakubeApi(HttpJsonClient transport){this.transport=transport;}
 public JobResult submit(String org,String workspace,String template,String branch) throws IOException,InterruptedException {
  Identifiers.required(org,"organizationId");Identifiers.required(workspace,"workspaceId");Identifiers.required(template,"templateId");
  ObjectNode request=HttpJsonClient.JSON.createObjectNode();ObjectNode data=request.putObject("data");
  data.put("type","job");ObjectNode attrs=data.putObject("attributes");attrs.put("templateReference",template);
  if(branch!=null&&!branch.isBlank()) attrs.put("overrideBranch",validBranch(branch));
  data.putObject("relationships").putObject("workspace").putObject("data")
    .put("type","workspace").put("id",workspace);
  return parse(transport.request("POST","/api/v1/organization/"+org+"/job",request));
 }
 public JobResult status(String org,String id) throws IOException,InterruptedException {
  return parse(transport.request("GET","/api/v1/organization/"+Identifiers.required(org,"organizationId")+
    "/job/"+Identifiers.required(id,"remoteId"),null));
 }
 private static String validBranch(String value) {
  if(value==null || value.isBlank() || value.length()>256 || value.chars().anyMatch(c->c<32 || c==127))
   throw new IllegalArgumentException("branch must be 1..256 characters without control characters");
  return value;
 }
 private static JobResult parse(JsonNode response) throws IOException {
  return HttpJsonClient.parse(response,SUCCESS,FAILED,Set.of());
 }
}
