package io.jenkins.plugins.iac.terrakube;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import hudson.model.Result;
import hudson.util.Secret;
import io.jenkins.plugins.iac.core.Connection;
import io.jenkins.plugins.iac.core.HttpJsonClient;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** terrakubeWorkspace as a Declarative stage option against an in-memory stub of the Terrakube API. */
@WithJenkins
class TerrakubeWorkspaceDeclarativeTest {
 private static final String SECRET_VALUE="db-password-value";
 private static final String WORKSPACES="/api/v1/organization/org-1/workspace";
 private JenkinsRule j;
 private HttpServer terrakube;
 /** Workspaces of organization acme (org-1) as [id, name], in creation order; names need not be unique. */
 private final List<String[]> workspaces=new CopyOnWriteArrayList<>();
 /** Variables by workspace ID, as "CATEGORY:key". */
 private final Map<String,List<String>> vars=new ConcurrentHashMap<>();
 private final List<JsonNode> creates=new CopyOnWriteArrayList<>(),varCreates=new CopyOnWriteArrayList<>();
 private final List<String> jobs=new CopyOnWriteArrayList<>();
 /** When set, the next create answers 409, as if another build had just created this workspace. */
 private volatile String createdConcurrently;
 /** When set, a create succeeds and another workspace with this name appears too, as a race the 409 check misses. */
 private volatile String duplicatedConcurrently;
 /** Variable key whose create answers 400. */
 private volatile String failingVariable;

 @BeforeEach void setUp(JenkinsRule rule) throws Exception {
  j=rule;
  terrakube=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  terrakube.createContext("/",this::handle);
  terrakube.start();
  SystemCredentialsProvider.getInstance().getCredentials().add(
    new StringCredentialsImpl(CredentialsScope.GLOBAL,"terrakube-token","Terrakube token",Secret.fromString("not-a-real-token")));
  SystemCredentialsProvider.getInstance().getCredentials().add(
    new StringCredentialsImpl(CredentialsScope.GLOBAL,"db-password","DB password",Secret.fromString(SECRET_VALUE)));
  SystemCredentialsProvider.getInstance().save();
  Connection c=new Connection("tk-test","http://127.0.0.1:"+terrakube.getAddress().getPort(),"terrakube-token");
  c.setAllowHttp(true);
  TerrakubeConnections.get().setConnections(List.of(c));
 }
 @AfterEach void tearDown(){terrakube.stop(0);}

 private void handle(HttpExchange exchange) throws IOException {
  String path=exchange.getRequestURI().getRawPath(),method=exchange.getRequestMethod();
  if(path.equals("/api/v1/organization")){
   respond(exchange,200,"{\"data\":[{\"id\":\"org-1\",\"type\":\"organization\",\"attributes\":{\"name\":\"acme\"}}]}");
  }else if(path.equals(WORKSPACES)&&"POST".equals(method)){
   JsonNode body=HttpJsonClient.JSON.readTree(exchange.getRequestBody());
   creates.add(body);
   String name=body.path("data").path("attributes").path("name").asText();
   if(name.equals(createdConcurrently)){workspaces.add(new String[]{"ws-other",name});createdConcurrently=null;}
   if(workspaces.stream().anyMatch(w->w[1].equals(name))){respond(exchange,409,"{\"errors\":[{\"detail\":\"exists\"}]}");return;}
   String id="ws-new"+(workspaces.size()+1);
   workspaces.add(new String[]{id,name});
   if(name.equals(duplicatedConcurrently))workspaces.add(new String[]{"ws-twin",name});
   respond(exchange,201,"{\"data\":{\"id\":\""+id+"\",\"type\":\"workspace\",\"attributes\":{\"name\":\""+name+"\"}}}");
  }else if(path.equals(WORKSPACES)){
   StringBuilder b=new StringBuilder("{\"data\":[");
   for(int i=0;i<workspaces.size();i++)b.append(i==0?"":",").append("{\"id\":\"").append(workspaces.get(i)[0])
     .append("\",\"type\":\"workspace\",\"attributes\":{\"name\":\"").append(workspaces.get(i)[1]).append("\",\"deleted\":false}}");
   respond(exchange,200,b.append("]}").toString());
  }else if(path.startsWith(WORKSPACES+"/")&&path.endsWith("/variable")){
   String id=path.split("/")[6];
   List<String> list=vars.computeIfAbsent(id,k->new CopyOnWriteArrayList<>());
   if("POST".equals(method)){
    JsonNode body=HttpJsonClient.JSON.readTree(exchange.getRequestBody());
    varCreates.add(body);
    JsonNode a=body.path("data").path("attributes");
    if(a.path("key").asText().equals(failingVariable)){respond(exchange,400,"{\"errors\":[{\"detail\":\"bad\"}]}");return;}
    list.add(a.path("category").asText()+":"+a.path("key").asText());
    respond(exchange,201,"{\"data\":{\"id\":\"var-"+list.size()+"\",\"type\":\"variable\"}}");
    return;
   }
   StringBuilder b=new StringBuilder("{\"data\":[");
   for(int i=0;i<list.size();i++){
    String[] v=list.get(i).split(":",2);
    b.append(i==0?"":",").append("{\"type\":\"variable\",\"attributes\":{\"category\":\"").append(v[0]).append("\",\"key\":\"").append(v[1]).append("\"}}");
   }
   respond(exchange,200,b.append("]}").toString());
  }else if(path.equals("/api/v1/organization/org-1/template")){
   respond(exchange,200,"{\"data\":[{\"id\":\"tpl-1\",\"type\":\"template\",\"attributes\":{\"name\":\"plan\"}}]}");
  }else if(path.equals("/api/v1/organization/org-1/job")){
   jobs.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
   respond(exchange,201,"{\"data\":{\"id\":\"job-1\",\"attributes\":{\"status\":\"completed\"}}}");
  }else respond(exchange,404,"{\"errors\":[]}");
 }

 @Test void createsAWorkspaceFromARepositoryWithVariables() throws Exception {
  WorkflowRun b=j.buildAndAssertSuccess(pipeline(stage("source: 'https://github.com/acme/infra.git', branchName: 'main', "+
    "workingDirectory: '/stacks/network', vcsId: 'vcs-1', iacType: 'tofu', executionMode: 'remote', description: 'Network', "+
    "variables: [region: 'eu-west-1', node_count: 3], environmentVariables: [TF_LOG: 'INFO'], "+
    "sensitiveEnvironmentVariables: [DB_PASSWORD: 'db-password']")));
  assertEquals(1,creates.size());
  JsonNode data=creates.get(0).path("data"),a=data.path("attributes");
  assertEquals("workspace",data.path("type").asText());
  assertEquals("network",a.path("name").asText());
  assertEquals("1.9.5",a.path("terraformVersion").asText());
  assertEquals("https://github.com/acme/infra.git",a.path("source").asText());
  assertEquals("main",a.path("branch").asText());
  assertEquals("/stacks/network",a.path("folder").asText());
  assertEquals("tofu",a.path("iacType").asText());
  assertEquals("remote",a.path("executionMode").asText());
  assertEquals("Network",a.path("description").asText());
  assertEquals("vcs-1",data.path("relationships").path("vcs").path("data").path("id").asText());
  assertEquals(List.of("TERRAFORM:region","TERRAFORM:node_count","ENV:TF_LOG","ENV:DB_PASSWORD"),vars.get("ws-new1"));
  assertEquals("3",varCreates.get(1).path("data").path("attributes").path("value").asText());
  JsonNode secret=varCreates.get(3).path("data").path("attributes");
  assertEquals(SECRET_VALUE,secret.path("value").asText());
  assertTrue(secret.path("sensitive").asBoolean());
  assertFalse(varCreates.get(0).path("data").path("attributes").path("sensitive").asBoolean());
  j.assertLogContains("IaC terrakube: creating workspace 'network' in organization 'acme' from https://github.com/acme/infra.git branch main with "+
    "variables [region, node_count], environment variables [TF_LOG], sensitive environment variables [DB_PASSWORD]",b);
  j.assertLogContains("created workspace ws-new1",b);
  j.assertLogNotContains(SECRET_VALUE,b);
  j.assertLogNotContains("eu-west-1",b);
  j.assertLogContains("workspace body",b);
 }

 @Test void withoutASourceTheWorkspaceIsCliDriven() throws Exception {
  WorkflowRun b=j.buildAndAssertSuccess(pipeline(stage(null)));
  JsonNode data=creates.get(0).path("data"),a=data.path("attributes");
  assertEquals("empty",a.path("source").asText());
  assertEquals("remote-content",a.path("branch").asText());
  assertEquals(List.of("name","terraformVersion","source","branch","folder"),fieldNames(a));
  assertEquals("/",a.path("folder").asText());
  assertFalse(data.has("relationships"));
  j.assertLogContains("for CLI-driven runs",b);
 }

 @Test void keepsAnExistingWorkspaceUnchanged() throws Exception {
  workspaces.add(new String[]{"ws-existing","network"});
  WorkflowRun b=j.buildAndAssertSuccess(pipeline(stage("variables: [region: 'eu-west-1']")));
  assertEquals(0,creates.size());
  assertEquals(0,varCreates.size());
  j.assertLogContains("found existing workspace ws-existing; configuration and variables not compared or changed",b);
  j.assertLogContains("workspace body",b);
 }

 @Test void ifExistsFailRejectsAnExistingWorkspace() throws Exception {
  workspaces.add(new String[]{"ws-existing","network"});
  WorkflowRun b=j.buildAndAssertStatus(Result.FAILURE,pipeline(stage("ifExists: 'fail'")));
  j.assertLogContains("Terrakube workspace 'network' in organization 'acme' already exists: ws-existing (ifExists: 'fail')",b);
  j.assertLogNotContains("workspace body",b);
 }

 @Test void severalWorkspacesWithTheNameFail() throws Exception {
  workspaces.add(new String[]{"ws-a","network"});workspaces.add(new String[]{"ws-b","network"});
  WorkflowRun b=j.buildAndAssertStatus(Result.FAILURE,pipeline(stage(null)));
  j.assertLogContains("2 Terrakube workspaces are named 'network' in organization 'acme' [ws-a, ws-b]",b);
  assertEquals(0,creates.size());
 }

 @Test void aConcurrentCreateRejectedWith409KeepsTheOtherWorkspace() throws Exception {
  createdConcurrently="network";
  WorkflowRun b=j.buildAndAssertSuccess(pipeline(stage("variables: [region: 'eu-west-1']")));
  assertEquals(1,creates.size());
  assertEquals(0,varCreates.size());
  j.assertLogContains("was created at the same time by someone else",b);
  j.assertLogContains("found existing workspace ws-other",b);
 }

 @Test void aConcurrentCreateThatTheCheckMissesFails() throws Exception {
  duplicatedConcurrently="network";
  WorkflowRun b=j.buildAndAssertStatus(Result.FAILURE,pipeline(stage("variables: [region: 'eu-west-1']")));
  j.assertLogContains("Another workspace named 'network' was created in organization 'acme' at the same time: [ws-new1, ws-twin]",b);
  assertEquals(0,varCreates.size());
  j.assertLogNotContains("workspace body",b);
 }

 @Test void aMissingCredentialFailsBeforeCreating() throws Exception {
  WorkflowRun b=j.buildAndAssertStatus(Result.FAILURE,pipeline(stage("sensitiveVariables: [token: 'no-such-credential']")));
  j.assertLogContains("Missing or inaccessible Secret text credential no-such-credential for sensitive variable token",b);
  assertEquals(0,creates.size());
 }

 @Test void aFailedVariableNamesTheWorkspace() throws Exception {
  failingVariable="node_count";
  WorkflowRun b=j.buildAndAssertStatus(Result.FAILURE,pipeline(stage("variables: [region: 'eu-west-1', node_count: 3]")));
  j.assertLogContains("Terrakube workspace ws-new1 was created, but its TERRAFORM variable node_count could not be set",b);
  j.assertLogNotContains("workspace body",b);
 }

 @Test void aCreatedWorkspaceCanBeUsedByNameInALaterStage() throws Exception {
  j.buildAndAssertSuccess(pipeline(stage(null)+
    "stage('plan') {\n options { terrakubeProvision(connection: 'tk-test', organizationName: 'acme', workspaceName: 'network', "+
    "templateName: 'plan') }\n steps { echo 'plan body' }\n}\n"));
  assertEquals(1,jobs.size());
  assertTrue(jobs.get(0).contains("\"id\":\"ws-new1\""),jobs.get(0));
 }

 @Test void terraformVersionIsRequired() throws Exception {
  WorkflowRun b=j.buildAndAssertStatus(Result.FAILURE,pipeline(
    "stage('workspace') {\n options { terrakubeWorkspace(connection: 'tk-test', organizationName: 'acme', workspaceName: 'network') }\n"+
    " steps { echo 'workspace body' }\n}\n"));
  j.assertLogContains("terraformVersion is required in the terrakubeWorkspace stage options",b);
  assertEquals(0,creates.size());
 }
 @Test void sourceNeedsABranch() throws Exception {
  assertRejected("source: 'https://github.com/acme/infra.git'","source needs branchName");
 }
 @Test void repositoryOptionsNeedASource() throws Exception {
  assertRejected("vcsId: 'vcs-1'","branchName, workingDirectory and vcsId need source");
 }
 @Test void aVariableCannotBePlainAndSensitive() throws Exception {
  assertRejected("environmentVariables: [TOKEN: 'x'], sensitiveEnvironmentVariables: [TOKEN: 'db-password']",
    "Variable TOKEN is set in both environmentVariables and sensitiveEnvironmentVariables");
 }
 @Test void executionModeIsChecked() throws Exception {
  assertRejected("executionMode: 'agent'","executionMode must be remote or local");
 }
 @Test void iacTypeIsChecked() throws Exception {
  assertRejected("iacType: 'pulumi'","iacType must be terraform or tofu");
 }

 private void assertRejected(String args,String message) throws Exception {
  WorkflowRun b=j.buildAndAssertStatus(Result.FAILURE,pipeline(stage(args)));
  j.assertLogContains(message,b);
  j.assertLogNotContains("creating workspace",b);
  j.assertLogNotContains("workspace body",b);
  assertEquals(0,creates.size());
 }
 private static String stage(String args){
  return "stage('workspace') {\n options { terrakubeWorkspace(connection: 'tk-test', organizationName: 'acme', workspaceName: 'network', "+
    "terraformVersion: '1.9.5'"+(args==null?"":", "+args)+") }\n steps { echo 'workspace body' }\n}\n";
 }
 private WorkflowJob pipeline(String stages) throws Exception {
  WorkflowJob p=j.createProject(WorkflowJob.class);
  p.setDefinition(new CpsFlowDefinition("pipeline {\n agent none\n stages {\n"+stages+"\n }\n}",true));
  return p;
 }
 private static List<String> fieldNames(JsonNode node){List<String> names=new ArrayList<>();node.fieldNames().forEachRemaining(names::add);return names;}
 private static void respond(HttpExchange exchange,int code,String body) throws IOException {
  byte[] b=body.getBytes(StandardCharsets.UTF_8);
  exchange.getResponseHeaders().set("Content-Type","application/vnd.api+json");
  exchange.sendResponseHeaders(code,b.length);
  try(OutputStream o=exchange.getResponseBody()){o.write(b);}
 }
}
