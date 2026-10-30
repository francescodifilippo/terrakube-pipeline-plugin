package io.jenkins.plugins.iac.terrakube;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import hudson.model.Result;
import hudson.util.Secret;
import io.jenkins.plugins.iac.core.Connection;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
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

/** Declarative stage options against a stub Terrakube API, with agent none. */
@WithJenkins
class TerrakubeDeclarativeTest {
 private JenkinsRule j;
 private HttpServer terrakube;
 private final List<String> submits=new CopyOnWriteArrayList<>();
 private final Queue<String> statuses=new ConcurrentLinkedQueue<>();
 /** Every request other than job submit and status: method, path and decoded query. */
 private final List<String> lookups=new CopyOnWriteArrayList<>();
 /** Collections served to lookups, unfiltered (the plugin compares names itself); other paths answer 404. */
 private static final Map<String,String> COLLECTIONS=Map.of(
   "/api/v1/organization","{\"data\":[{\"id\":\"org-1\",\"type\":\"organization\",\"attributes\":{\"name\":\"acme\"}},"+
     "{\"id\":\"org-2\",\"type\":\"organization\",\"attributes\":{\"name\":\"other\"}}]}",
   "/api/v1/organization/org-1/workspace","{\"data\":[{\"id\":\"ws-1\",\"type\":\"workspace\",\"attributes\":{\"name\":\"network\",\"deleted\":false}},"+
     "{\"id\":\"ws-2\",\"type\":\"workspace\",\"attributes\":{\"name\":\"my ws\",\"deleted\":false}}]}",
   "/api/v1/organization/org-1/template","{\"data\":[{\"id\":\"tpl-1\",\"type\":\"template\",\"attributes\":{\"name\":\"apply\"}},"+
     "{\"id\":\"tpl-2\",\"type\":\"template\",\"attributes\":{\"name\":\"twin\"}},{\"id\":\"tpl-3\",\"type\":\"template\",\"attributes\":{\"name\":\"twin\"}}]}");

 @BeforeEach void setUp(JenkinsRule rule) throws Exception {
  j=rule;
  terrakube=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  terrakube.createContext("/api/v1/organization/org-1/job",exchange -> {
   submits.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
   respond(exchange,201,"pending");
  });
  terrakube.createContext("/api/v1/organization/org-1/job/job-1",exchange -> {
   String next=statuses.poll();
   respond(exchange,200,next==null?"running":next);
  });
  terrakube.createContext("/",exchange -> {
   String path=exchange.getRequestURI().getRawPath(),query=exchange.getRequestURI().getQuery();
   lookups.add(exchange.getRequestMethod()+" "+path+(query==null?"":"?"+query));
   String list=COLLECTIONS.get(path);
   if(list==null){exchange.sendResponseHeaders(404,-1);exchange.close();return;}
   byte[] body=list.getBytes(StandardCharsets.UTF_8);
   exchange.getResponseHeaders().set("Content-Type","application/vnd.api+json");
   exchange.sendResponseHeaders(200,body.length);
   try(OutputStream o=exchange.getResponseBody()){o.write(body);}
  });
  terrakube.start();
  SystemCredentialsProvider.getInstance().getCredentials().add(
    new StringCredentialsImpl(CredentialsScope.GLOBAL,"terrakube-token","Terrakube token",Secret.fromString("not-a-real-token")));
  SystemCredentialsProvider.getInstance().save();
  Connection c=new Connection("tk-test","http://127.0.0.1:"+terrakube.getAddress().getPort(),"terrakube-token");
  c.setAllowHttp(true);
  TerrakubeConnections.get().setConnections(List.of(c));
 }

 @AfterEach void tearDown(){terrakube.stop(0);}

 @Test void asyncJobReportsApprovalPendingThenAwaits() throws Exception {
  statuses.add("waitingApproval");statuses.add("completed");
  WorkflowRun b=j.buildAndAssertSuccess(pipeline(
    "stage('submit') {\n options {\n terrakubeProvision(connection: 'tk-test', organizationId: 'org-1', workspaceId: 'ws-1', templateId: 'tpl-1', branch: 'main', operationKey: 'deploy', waitForCompletion: false)\n }\n"+
    " steps {\n echo 'submitted'\n }\n}\n"+
    "stage('await') {\n options {\n terrakubeAwait(connection: 'tk-test', operationKey: 'deploy', pollingSeconds: 5)\n }\n"+
    " steps {\n echo 'awaited'\n }\n}"));
  assertEquals(1,submits.size());
  assertTrue(submits.get(0).contains("\"templateReference\":\"tpl-1\""),submits.get(0));
  assertTrue(submits.get(0).contains("\"overrideBranch\":\"main\""),submits.get(0));
  j.assertLogContains("submitted",b);
  j.assertLogContains("approval pending for ID job-1",b);
  j.assertLogContains("completed ID job-1 with status completed",b);
  j.assertLogContains("awaited",b);
 }

 @Test void noChangesJobSucceeds() throws Exception {
  statuses.add("noChanges");
  WorkflowRun b=j.buildAndAssertSuccess(pipeline(
    "stage('plan') {\n options {\n terrakubeProvision(connection: 'tk-test', organizationId: 'org-1', workspaceId: 'ws-1', templateId: 'tpl-1', pollingSeconds: 5)\n }\n"+
    " steps {\n echo 'plan body'\n }\n}"));
  j.assertLogContains("completed ID job-1 with status nochanges",b);
  j.assertLogContains("plan body",b);
 }

 @Test void rejectedJobFailsTheStage() throws Exception {
  statuses.add("waitingApproval");statuses.add("rejected");
  WorkflowRun b=j.buildAndAssertStatus(Result.FAILURE,pipeline(
    "stage('apply') {\n options {\n terrakubeProvision(connection: 'tk-test', organizationId: 'org-1', workspaceId: 'ws-1', templateId: 'tpl-1', pollingSeconds: 5)\n }\n"+
    " steps {\n echo 'applied body'\n }\n}"));
  j.assertLogContains("Remote operation job-1 failed: rejected",b);
  j.assertLogNotContains("applied body",b);
 }

 @Test void idsMakeNoLookup() throws Exception {
  statuses.add("completed");
  WorkflowRun b=j.buildAndAssertSuccess(pipeline(
    "stage('plan') {\n options {\n terrakubeProvision(connection: 'tk-test', organizationId: 'org-1', workspaceId: 'ws-1', templateId: 'tpl-1', pollingSeconds: 5)\n }\n"+
    " steps {\n echo 'plan body'\n }\n}"));
  assertEquals(List.of(),lookups);
  assertTrue(submits.get(0).contains("\"id\":\"ws-1\""),submits.get(0));
  j.assertLogNotContains("resolved",b);
  j.assertLogContains("plan body",b);
 }

 @Test void namesAreResolvedOnceThenAwaited() throws Exception {
  statuses.add("waitingApproval");statuses.add("completed");
  WorkflowRun b=j.buildAndAssertSuccess(pipeline(
    "stage('submit') {\n options {\n terrakubeProvision(connection: 'tk-test', organizationName: 'acme', workspaceName: 'my ws', templateName: 'apply', branch: 'main', operationKey: 'deploy', waitForCompletion: false)\n }\n"+
    " steps {\n echo 'submitted'\n }\n}\n"+
    "stage('await') {\n options {\n terrakubeAwait(connection: 'tk-test', operationKey: 'deploy', pollingSeconds: 5)\n }\n"+
    " steps {\n echo 'awaited'\n }\n}"));
  assertEquals(List.of("GET /api/v1/organization?filter[organization]=name=='acme'",
    "GET /api/v1/organization/org-1/workspace?filter[workspace]=name=='my ws';deleted==false",
    "GET /api/v1/organization/org-1/template?filter[template]=name=='apply'"),lookups);
  assertEquals(1,submits.size());
  assertTrue(submits.get(0).contains("\"id\":\"ws-2\""),submits.get(0));
  assertTrue(submits.get(0).contains("\"templateReference\":\"tpl-1\""),submits.get(0));
  assertTrue(submits.get(0).contains("\"overrideBranch\":\"main\""),submits.get(0));
  j.assertLogContains("IaC terrakube: resolved target to ws-2",b);
  j.assertLogContains("IaC terrakube: resolved organizationId to org-1",b);
  j.assertLogContains("IaC terrakube: resolved templateId to tpl-1",b);
  j.assertLogContains("submitting target ws-2 with operationKey deploy",b);
  // status polling (/api/v1/organization/org-1/job/job-1) only works with the resolved organizationId in the metadata
  j.assertLogContains("approval pending for ID job-1",b);
  j.assertLogContains("completed ID job-1 with status completed",b);
  j.assertLogContains("awaited",b);
 }

 @Test void workspaceNameWithOrganizationAndTemplateIds() throws Exception {
  statuses.add("completed");
  WorkflowRun b=j.buildAndAssertSuccess(pipeline(
    "stage('plan') {\n options {\n terrakubeProvision(connection: 'tk-test', organizationId: 'org-1', workspaceName: 'network', templateId: 'tpl-9', pollingSeconds: 5)\n }\n"+
    " steps {\n echo 'plan body'\n }\n}"));
  assertEquals(List.of("GET /api/v1/organization/org-1/workspace?filter[workspace]=name=='network';deleted==false"),lookups);
  assertTrue(submits.get(0).contains("\"id\":\"ws-1\""),submits.get(0));
  assertTrue(submits.get(0).contains("\"templateReference\":\"tpl-9\""),submits.get(0));
  j.assertLogContains("IaC terrakube: resolved target to ws-1",b);
  j.assertLogNotContains("resolved organizationId",b);
  j.assertLogNotContains("resolved templateId",b);
  j.assertLogContains("plan body",b);
 }

 @Test void workspaceIdWithOrganizationAndTemplateNames() throws Exception {
  statuses.add("completed");
  WorkflowRun b=j.buildAndAssertSuccess(pipeline(
    "stage('plan') {\n options {\n terrakubeProvision(connection: 'tk-test', organizationName: 'acme', workspaceId: 'ws-7', templateName: 'apply', pollingSeconds: 5)\n }\n"+
    " steps {\n echo 'plan body'\n }\n}"));
  assertEquals(List.of("GET /api/v1/organization?filter[organization]=name=='acme'",
    "GET /api/v1/organization/org-1/template?filter[template]=name=='apply'"),lookups);
  assertTrue(submits.get(0).contains("\"id\":\"ws-7\""),submits.get(0));
  assertTrue(submits.get(0).contains("\"templateReference\":\"tpl-1\""),submits.get(0));
  j.assertLogNotContains("resolved target",b);
  j.assertLogContains("IaC terrakube: resolved organizationId to org-1",b);
  j.assertLogContains("plan body",b);
 }

 @Test void unknownWorkspaceNameFailsBeforeSubmitting() throws Exception {
  WorkflowRun b=j.buildAndAssertStatus(Result.FAILURE,pipeline(
    "stage('plan') {\n options {\n terrakubeProvision(connection: 'tk-test', organizationName: 'acme', workspaceName: 'Network', templateName: 'apply')\n }\n"+
    " steps {\n echo 'plan body'\n }\n}"));
  j.assertLogContains("No Terrakube workspace named 'Network' in organization 'acme'; check workspaceName or use workspaceId",b);
  assertEquals(2,lookups.size());
  assertEquals(0,submits.size());
  j.assertLogNotContains("submitting target",b);
  j.assertLogNotContains("plan body",b);
 }

 @Test void ambiguousTemplateNameFailsBeforeSubmitting() throws Exception {
  WorkflowRun b=j.buildAndAssertStatus(Result.FAILURE,pipeline(
    "stage('plan') {\n options {\n terrakubeProvision(connection: 'tk-test', organizationId: 'org-1', workspaceId: 'ws-1', templateName: 'twin')\n }\n"+
    " steps {\n echo 'plan body'\n }\n}"));
  j.assertLogContains("2 Terrakube templates are named 'twin' in organization org-1 [tpl-2, tpl-3]; use templateId",b);
  assertEquals(0,submits.size());
  j.assertLogNotContains("plan body",b);
 }

 @Test void lookupHttpErrorsFailTheStage() throws Exception {
  WorkflowRun b=j.buildAndAssertStatus(Result.FAILURE,pipeline(
    "stage('plan') {\n options {\n terrakubeProvision(connection: 'tk-test', organizationId: 'org-404', workspaceName: 'network', templateId: 'tpl-1')\n }\n"+
    " steps {\n echo 'plan body'\n }\n}"));
  j.assertLogContains("IaC API HTTP 404 for GET /api/v1/organization/org-404/workspace",b);
  assertEquals(1,lookups.size());
  assertEquals(0,submits.size());
  j.assertLogNotContains("plan body",b);
 }

 @Test void eachKindNeedsItsIdOrName() throws Exception {
  assertRejected("connection: 'tk-test', workspaceId: 'ws-1', templateId: 'tpl-1'",
    "terrakubeProvision needs organizationId or organizationName in the stage options");
  assertRejected("connection: 'tk-test', organizationName: 'acme', templateName: 'apply'",
    "terrakubeProvision needs workspaceId or workspaceName in the stage options");
  assertRejected("connection: 'tk-test', organizationId: 'org-1', workspaceName: 'network'",
    "terrakubeProvision needs templateId or templateName in the stage options");
 }
 @Test void idAndNameAreExclusive() throws Exception {
  assertRejected("connection: 'tk-test', organizationId: 'org-1', organizationName: 'acme', workspaceId: 'ws-1', templateId: 'tpl-1'",
    "Use either organizationId or organizationName in the terrakubeProvision stage options, not both");
  assertRejected("connection: 'tk-test', organizationId: 'org-1', workspaceId: 'ws-1', workspaceName: 'network', templateId: 'tpl-1'",
    "Use either workspaceId or workspaceName in the terrakubeProvision stage options, not both");
  assertRejected("connection: 'tk-test', organizationId: 'org-1', workspaceId: 'ws-1', templateId: 'tpl-1', templateName: 'apply'",
    "Use either templateId or templateName in the terrakubeProvision stage options, not both");
 }
 @Test void invalidNameIsRejected() throws Exception {
  assertRejected("connection: 'tk-test', organizationName: 'acme', workspaceName: 'net\\nwork', templateId: 'tpl-1'",
    "workspaceName must contain 1..255 characters without control characters");
 }

 private void assertRejected(String args,String message) throws Exception {
  WorkflowRun b=j.buildAndAssertStatus(Result.FAILURE,pipeline(
    "stage('plan') {\n options {\n terrakubeProvision("+args+")\n }\n steps {\n echo 'plan body'\n }\n}"));
  j.assertLogContains(message,b);
  j.assertLogNotContains("submitting target",b);
  j.assertLogNotContains("plan body",b);
  assertEquals(0,submits.size());
  assertEquals(List.of(),lookups);
 }
 private WorkflowJob pipeline(String stages) throws Exception {
  WorkflowJob p=j.createProject(WorkflowJob.class);
  p.setDefinition(new CpsFlowDefinition("pipeline {\n agent none\n stages {\n"+stages+"\n }\n}",true));
  return p;
 }

 private static void respond(HttpExchange exchange,int code,String status) throws IOException {
  byte[] body=("{\"data\":{\"id\":\"job-1\",\"type\":\"job\",\"attributes\":{\"status\":\""+status+"\"}}}").getBytes(StandardCharsets.UTF_8);
  exchange.getResponseHeaders().set("Content-Type","application/vnd.api+json");
  exchange.sendResponseHeaders(code,body.length);
  try(OutputStream o=exchange.getResponseBody()){o.write(body);}
 }
}
