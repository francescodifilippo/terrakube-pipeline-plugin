package io.jenkins.plugins.iac.terrakube;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jenkins.plugins.iac.core.*;
import java.io.IOException;
import java.util.*;
/** Jobs are driven by Terrakube's own templates, not by OTF plan/apply flags. */
public final class TerrakubeApi {
 private final HttpJsonClient transport;
 /** Terrakube JobStatus values, lowercased by the core: terminal = completed, noChanges, rejected, cancelled, failed. */
 private static final Set<String> SUCCESS=Set.of("completed","nochanges");
 private static final Set<String> FAILED=Set.of("failed","rejected","cancelled");
 private static final Set<String> APPROVAL=Set.of("waitingapproval");
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
 /**
  * Rows Elide returns when no page[...] is sent: its default page size, raised to 10000 by Terrakube's
  * {@code @Paginate} on workspace. Lookups cannot page instead, because Terrakube's read checks make Elide reject
  * page[...] for these types ("Cannot paginate"), so a full page means the list may be incomplete.
  */
 static final int ORGANIZATION_ROWS=500,WORKSPACE_ROWS=10000,TEMPLATE_ROWS=500;
 public String organizationIdByName(String name) throws IOException,InterruptedException {
  return idByName("organization","/api/v1/organization",name,"",ORGANIZATION_ROWS);
 }
 /** {@code organization} names the organization in messages, for example {@code 'acme'}. Deleted workspaces never match. */
 public String workspaceIdByName(String organizationId,String organization,String name) throws IOException,InterruptedException {
  return idByName("workspace",organizationPath(organizationId)+"/workspace",name," in organization "+organization,WORKSPACE_ROWS);
 }
 public String templateIdByName(String organizationId,String organization,String name) throws IOException,InterruptedException {
  return idByName("template",organizationPath(organizationId)+"/template",name," in organization "+organization,TEMPLATE_ROWS);
 }
 private static String organizationPath(String organizationId){
  return "/api/v1/organization/"+HttpJsonClient.pathSegment(Identifiers.required(organizationId,"organizationId"));
 }
 /**
  * ID of the one {@code kind} named exactly {@code name}, read with an Elide RSQL filter on the JSON:API collection.
  * The filter only narrows the list: a leading or trailing {@code *} is a wildcard even when quoted, and the database
  * collation may ignore case, so returned names are compared again before counting.
  */
 private String idByName(String kind,String path,String name,String where,int rows) throws IOException,InterruptedException {
  Map<String,String> query=new LinkedHashMap<>();
  query.put("filter["+kind+"]","name=="+rsql(name)+("workspace".equals(kind)?";deleted==false":""));
  JsonNode response=transport.request("GET",path,query,null),data=response.path("data");
  if(!data.isArray())throw new IOException("Terrakube did not return a list of "+kind+"s");
  if(data.size()>=rows||response.path("links").hasNonNull("next")||response.path("meta").path("page").path("totalRecords").asLong(0)>data.size())
   throw new IOException("Terrakube returned "+data.size()+" or more "+kind+"s for the name '"+name+"'"+where+
     ", so the list may be incomplete; use "+kind+"Id");
  List<String> ids=new ArrayList<>();
  for(JsonNode item:data){
   JsonNode attributes=item.path("attributes");
   if(kind.equals(item.path("type").asText())&&name.equals(attributes.path("name").asText(null))&&!attributes.path("deleted").asBoolean(false))
    ids.add(item.path("id").asText(null));
  }
  if(ids.isEmpty())throw new IllegalArgumentException("No Terrakube "+kind+" named '"+name+"'"+where+"; check "+kind+"Name or use "+kind+"Id");
  if(ids.size()>1)throw new IllegalArgumentException(ids.size()+" Terrakube "+kind+"s are named '"+name+"'"+where+" "+ids+"; use "+kind+"Id");
  return Identifiers.required(ids.get(0),"Terrakube "+kind+" ID");
 }
 /** RSQL single-quoted string: the parser drops a backslash and keeps the next character. */
 static String rsql(String value){return "'"+value.replace("\\","\\\\").replace("'","\\'")+"'";}
 static String validBranch(String value) {
  if(value==null || value.isBlank() || value.length()>256 || value.chars().anyMatch(c->c<32 || c==127))
   throw new IllegalArgumentException("branch must be 1..256 characters without control characters");
  return value;
 }
 private static JobResult parse(JsonNode response) throws IOException {
  return HttpJsonClient.parse(response,SUCCESS,FAILED,APPROVAL);
 }
}
