package io.jenkins.plugins.iac.terrakube;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.jenkins.plugins.iac.core.HttpJsonClient;
import io.jenkins.plugins.iac.core.IacHttpException;
import io.jenkins.plugins.iac.core.ResolvedTarget;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Name lookups against a stub of Terrakube's JSON:API collections, which returns whatever the test sets. */
class TerrakubeLookupTest {
 private HttpServer stub;
 private HttpJsonClient client;
 private final List<String> requests=new CopyOnWriteArrayList<>();
 private volatile int code=200;
 private volatile String body="{\"data\":[]}";

 @BeforeEach void setUp() throws IOException {
  stub=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  stub.createContext("/",exchange -> {
   requests.add(exchange.getRequestMethod()+" "+exchange.getRequestURI().getRawPath()+"?"+exchange.getRequestURI().getRawQuery());
   byte[] b=(code==200?body:"{\"errors\":[{\"status\":\""+code+"\"}]}").getBytes(StandardCharsets.UTF_8);
   exchange.getResponseHeaders().set("Content-Type","application/vnd.api+json");
   exchange.sendResponseHeaders(code,b.length);try(OutputStream o=exchange.getResponseBody()){o.write(b);}
  });
  stub.start();
  client=new HttpJsonClient("http://127.0.0.1:"+stub.getAddress().getPort(),"unit-test-token",true);
 }
 @AfterEach void tearDown(){client.close();stub.stop(0);}

 private TerrakubeApi api(){return new TerrakubeApi(client);}
 /** JSON:API list of {@code type} items, each given as id, name and optionally "deleted". */
 private static String list(String type,String... items){
  StringJoiner data=new StringJoiner(",","{\"data\":[","]}");
  for(String item:items){
   String[] f=item.split("\\|",-1);
   data.add("{\"id\":\""+f[0]+"\",\"type\":\""+type+"\",\"attributes\":{\"name\":\""+f[1].replace("\\","\\\\").replace("\"","\\\"")+"\""+
     (f.length>2?",\"deleted\":"+f[2]:"")+"}}");
  }
  return data.toString();
 }
 private String filter(){
  String query=requests.get(requests.size()-1);
  return URLDecoder.decode(query.substring(query.indexOf('=')+1),StandardCharsets.UTF_8);
 }

 @Test void organizationFound() throws Exception {
  body=list("organization","org-1|acme");
  assertEquals("org-1",api().organizationIdByName("acme"));
  assertEquals(List.of("GET /api/v1/organization?filter%5Borganization%5D=name%3D%3D%27acme%27"),requests);
 }
 @Test void workspaceFoundInItsOrganization() throws Exception {
  body=list("workspace","ws-1|my ws");
  assertEquals("ws-1",api().workspaceIdByName("org-1","'acme'","my ws"));
  assertEquals(List.of("GET /api/v1/organization/org-1/workspace?filter%5Bworkspace%5D=name%3D%3D%27my%20ws%27%3Bdeleted%3D%3Dfalse"),requests);
 }
 @Test void templateFoundInItsOrganization() throws Exception {
  body=list("template","tpl-1|Plan and apply");
  assertEquals("tpl-1",api().templateIdByName("org-1","'acme'","Plan and apply"));
  assertEquals(List.of("GET /api/v1/organization/org-1/template?filter%5Btemplate%5D=name%3D%3D%27Plan%20and%20apply%27"),requests);
 }

 @Test void noMatchNamesTheParameters(){
  assertEquals("No Terrakube organization named 'acme'; check organizationName or use organizationId",
    assertThrows(IllegalArgumentException.class,()->api().organizationIdByName("acme")).getMessage());
  assertEquals("No Terrakube workspace named 'network' in organization 'acme'; check workspaceName or use workspaceId",
    assertThrows(IllegalArgumentException.class,()->api().workspaceIdByName("org-1","'acme'","network")).getMessage());
  assertEquals("No Terrakube template named 'apply' in organization org-1; check templateName or use templateId",
    assertThrows(IllegalArgumentException.class,()->api().templateIdByName("org-1","org-1","apply")).getMessage());
 }
 @Test void severalMatchesListTheirIds(){
  body=list("organization","org-1|acme","org-2|acme");
  assertEquals("2 Terrakube organizations are named 'acme' [org-1, org-2]; use organizationId",
    assertThrows(IllegalArgumentException.class,()->api().organizationIdByName("acme")).getMessage());
  body=list("workspace","ws-1|network","ws-2|network");
  assertEquals("2 Terrakube workspaces are named 'network' in organization 'acme' [ws-1, ws-2]; use workspaceId",
    assertThrows(IllegalArgumentException.class,()->api().workspaceIdByName("org-1","'acme'","network")).getMessage());
  body=list("template","tpl-1|apply","tpl-2|apply");
  assertEquals("2 Terrakube templates are named 'apply' in organization 'acme' [tpl-1, tpl-2]; use templateId",
    assertThrows(IllegalArgumentException.class,()->api().templateIdByName("org-1","'acme'","apply")).getMessage());
 }
 @Test void comparesReturnedNamesExactly() throws Exception {
  // e.g. a case-insensitive database collation
  body=list("workspace","ws-1|Network","ws-2|network ","ws-3|network-dev","ws-4|network");
  assertEquals("ws-4",api().workspaceIdByName("org-1","'acme'","network"));
  body=list("workspace","ws-1|Network","ws-2|network ");
  assertThrows(IllegalArgumentException.class,()->api().workspaceIdByName("org-1","'acme'","network"));
  body="{\"data\":[{\"id\":\"ws-1\",\"type\":\"workspace\",\"attributes\":{}}]}";
  assertThrows(IllegalArgumentException.class,()->api().workspaceIdByName("org-1","'acme'","network"));
 }
 @Test void wildcardMatchesAreNotExact() throws Exception {
  // Elide treats a leading or trailing * as a wildcard even inside quotes, so the server may return more.
  body=list("workspace","ws-1|net","ws-2|xnet","ws-3|*net");
  assertEquals("ws-3",api().workspaceIdByName("org-1","'acme'","*net"));
  assertTrue(requests.get(0).contains("name%3D%3D%27%2Anet%27"),requests.get(0));
  body=list("template","tpl-1|apply","tpl-2|apply-all","tpl-3|apply*");
  assertEquals("tpl-3",api().templateIdByName("org-1","'acme'","apply*"));
  body=list("template","tpl-1|apply","tpl-2|apply-all");
  assertThrows(IllegalArgumentException.class,()->api().templateIdByName("org-1","'acme'","apply*"));
 }
 @Test void quotesNamesForRsql() throws Exception {
  String name="o'neil \\ *x* \"y\" ;,()==";
  body=list("organization","org-1|"+name);
  assertEquals("org-1",api().organizationIdByName(name));
  assertEquals("name=='o\\'neil \\\\ *x* \"y\" ;,()=='",filter());
  assertEquals("'a\\\\\\'b'",TerrakubeApi.rsql("a\\'b"));
 }
 @Test void encodesTheQueryAndTheOrganizationPath() throws Exception {
  body=list("workspace","ws-1|a&b=c/d?e#f%é+");
  assertEquals("ws-1",api().workspaceIdByName("org-1","'acme'","a&b=c/d?e#f%é+"));
  assertEquals("GET /api/v1/organization/org-1/workspace?filter%5Bworkspace%5D=name%3D%3D%27a%26b%3Dc%2Fd%3Fe%23f%25%C3%A9%2B%27%3Bdeleted%3D%3Dfalse",
    requests.get(0));
  assertThrows(IllegalArgumentException.class,()->api().workspaceIdByName("../x","'acme'","network"));
  assertEquals(1,requests.size());
 }
 @Test void deletedWorkspacesNeverMatch() throws Exception {
  body=list("workspace","ws-old|network|true","ws-1|network|false");
  assertEquals("ws-1",api().workspaceIdByName("org-1","'acme'","network"));
  body=list("workspace","ws-old|network|true");
  assertThrows(IllegalArgumentException.class,()->api().workspaceIdByName("org-1","'acme'","network"));
 }
 @Test void ignoresOtherResourceTypes(){
  body=list("workspace","org-1|acme");
  assertThrows(IllegalArgumentException.class,()->api().organizationIdByName("acme"));
 }
 @Test void rejectsAnInvalidId(){
  body=list("organization","org/../x|acme");
  assertThrows(IllegalArgumentException.class,()->api().organizationIdByName("acme"));
 }
 @Test void aFullPageMayBeTruncated() throws Exception {
  body=list("organization",IntStream.range(0,500).mapToObj(i->"org-"+i+"|"+(i==0?"acme":"acme"+i)).toArray(String[]::new));
  assertEquals("Terrakube returned 500 or more organizations for the name 'acme', so the list may be incomplete; use organizationId",
    assertThrows(IOException.class,()->api().organizationIdByName("acme")).getMessage());
  body=list("template",IntStream.range(0,500).mapToObj(i->"tpl-"+i+"|apply"+i).toArray(String[]::new));
  assertThrows(IOException.class,()->api().templateIdByName("org-1","'acme'","apply*"));
  body=list("workspace",IntStream.range(0,500).mapToObj(i->"ws-"+i+"|net"+i).toArray(String[]::new));
  assertThrows(IllegalArgumentException.class,()->api().workspaceIdByName("org-1","'acme'","net*"));
  body=list("workspace",IntStream.range(0,10000).mapToObj(i->"ws-"+i+"|net"+i).toArray(String[]::new));
  assertThrows(IOException.class,()->api().workspaceIdByName("org-1","'acme'","net*"));
 }
 @Test void paginationHintsMeanTheListIsIncomplete(){
  body="{\"data\":[{\"id\":\"org-1\",\"type\":\"organization\",\"attributes\":{\"name\":\"acme\"}}],\"links\":{\"next\":\"/api/v1/organization?page[number]=2\"}}";
  assertThrows(IOException.class,()->api().organizationIdByName("acme"));
  body="{\"data\":[{\"id\":\"org-1\",\"type\":\"organization\",\"attributes\":{\"name\":\"acme\"}}],\"meta\":{\"page\":{\"totalRecords\":2}}}";
  assertThrows(IOException.class,()->api().organizationIdByName("acme"));
 }
 @Test void rejectsAResponseWithoutAList(){
  body="{\"data\":{\"id\":\"org-1\",\"type\":\"organization\",\"attributes\":{\"name\":\"acme\"}}}";
  assertEquals("Terrakube did not return a list of organizations",
    assertThrows(IOException.class,()->api().organizationIdByName("acme")).getMessage());
 }
 @Test void httpErrorsPropagate(){
  code=403;
  assertEquals(403,assertThrows(IacHttpException.class,()->api().workspaceIdByName("org-1","'acme'","network")).statusCode());
 }

 @Test void backendUsesIdsWithoutCallingTerrakube() throws Exception {
  Map<String,String> parameters=Map.of("organizationId","org-1","templateId","tpl-1","branch","main");
  ResolvedTarget resolved=new TerrakubeBackend().resolve("tk-test","ws-1",parameters,null);
  assertEquals("ws-1",resolved.targetId());
  assertEquals(parameters,resolved.parameters());
  assertEquals(List.of(),requests);
 }
 @Test void backendNeedsAWorkspace(){
  assertThrows(IllegalArgumentException.class,()->new TerrakubeBackend().resolve("tk-test",null,Map.of("organizationId","org-1","templateId","tpl-1"),null));
  assertEquals(List.of(),requests);
 }
}
