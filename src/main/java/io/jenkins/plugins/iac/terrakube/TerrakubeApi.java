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
 /** Every workspace named exactly {@code name}, possibly none; same read as {@link #workspaceIdByName}. */
 List<String> workspaceIdsByName(String organizationId,String organization,String name) throws IOException,InterruptedException {
  return idsByName("workspace",organizationPath(organizationId)+"/workspace",name," in organization "+organization,WORKSPACE_ROWS);
 }
 /**
  * Creates a workspace in the organization ({@code POST /api/v1/organization/:id/workspace}). Terrakube answers 409
  * when a workspace with the name exists, but checks this in the application, so concurrent creates may both succeed.
  */
 String createWorkspace(String organizationId,ObjectNode attributes,ObjectNode relationships) throws IOException,InterruptedException {
  ObjectNode request=HttpJsonClient.JSON.createObjectNode();ObjectNode data=request.putObject("data");
  data.put("type","workspace");data.set("attributes",attributes.deepCopy());
  if(relationships.size()>0)data.set("relationships",relationships.deepCopy());
  JsonNode created=transport.request("POST",organizationPath(organizationId)+"/workspace",request).path("data");
  if(!"workspace".equals(created.path("type").asText()))throw new IOException("Terrakube did not return the created workspace");
  return Identifiers.required(created.path("id").asText(null),"Terrakube workspace ID");
 }
 /** Category and key ({@code TERRAFORM:region}, {@code ENV:TF_LOG}) of every variable of the workspace. */
 Set<String> variableKeys(String organizationId,String workspaceId) throws IOException,InterruptedException {
  Set<String> keys=new HashSet<>();
  for(JsonNode v:transport.request("GET",variablesPath(organizationId,workspaceId),null).path("data"))
   keys.add(v.path("attributes").path("category").asText()+":"+v.path("attributes").path("key").asText());
  return keys;
 }
 /** Creates a workspace variable; the value is sent, never logged. {@code category} is TERRAFORM or ENV. */
 void createVariable(String organizationId,String workspaceId,String category,String key,String value,boolean sensitive) throws IOException,InterruptedException {
  ObjectNode request=HttpJsonClient.JSON.createObjectNode();ObjectNode data=request.putObject("data");
  data.put("type","variable");
  data.putObject("attributes").put("key",key).put("value",value).put("category",category).put("hcl",false).put("sensitive",sensitive);
  transport.request("POST",variablesPath(organizationId,workspaceId),request);
 }
 private static String variablesPath(String organizationId,String workspaceId){
  return organizationPath(organizationId)+"/workspace/"+HttpJsonClient.pathSegment(Identifiers.required(workspaceId,"workspaceId"))+"/variable";
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
 private List<String> idsByName(String kind,String path,String name,String where,int rows) throws IOException,InterruptedException {
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
    ids.add(Identifiers.required(item.path("id").asText(null),"Terrakube "+kind+" ID"));
  }
  return ids;
 }
 private String idByName(String kind,String path,String name,String where,int rows) throws IOException,InterruptedException {
  List<String> ids=idsByName(kind,path,name,where,rows);
  if(ids.isEmpty())throw new IllegalArgumentException("No Terrakube "+kind+" named '"+name+"'"+where+"; check "+kind+"Name or use "+kind+"Id");
  if(ids.size()>1)throw new IllegalArgumentException(ids.size()+" Terrakube "+kind+"s are named '"+name+"'"+where+" "+ids+"; use "+kind+"Id");
  return ids.get(0);
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
