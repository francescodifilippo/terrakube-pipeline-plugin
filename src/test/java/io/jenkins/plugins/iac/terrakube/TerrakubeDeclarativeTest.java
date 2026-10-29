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
  terrakube.start();
  SystemCredentialsProvider.getInstance().getCredentials().add(
    new StringCredentialsImpl(CredentialsScope.GLOBAL,"terrakube-token","Terrakube token",Secret.fromString("not-a-real-token")));
  SystemCredentialsProvider.getInstance().save();
  Connection c=new Connection("tk-test","http://127.0.0.1:"+terrakube.getAddress().getPort(),"terrakube-token");
  c.setAllowHttp(true);
  TerrakubeConnections.get().setServers(List.of(c));
 }

 @AfterEach void tearDown(){terrakube.stop(0);}

 @Test void asyncJobReportsApprovalPendingThenAwaits() throws Exception {
  statuses.add("waitingApproval");statuses.add("completed");
  WorkflowRun b=j.buildAndAssertSuccess(pipeline(
    "stage('submit') {\n options {\n terrakubeProvision(server: 'tk-test', organizationId: 'org-1', workspaceId: 'ws-1', templateId: 'tpl-1', branch: 'main', operationKey: 'deploy', waitForCompletion: false)\n }\n"+
    " steps {\n echo 'submitted'\n }\n}\n"+
    "stage('await') {\n options {\n terrakubeAwait(server: 'tk-test', operationKey: 'deploy', pollingSeconds: 5)\n }\n"+
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
    "stage('plan') {\n options {\n terrakubeProvision(server: 'tk-test', organizationId: 'org-1', workspaceId: 'ws-1', templateId: 'tpl-1', pollingSeconds: 5)\n }\n"+
    " steps {\n echo 'plan body'\n }\n}"));
  j.assertLogContains("completed ID job-1 with status nochanges",b);
  j.assertLogContains("plan body",b);
 }

 @Test void rejectedJobFailsTheStage() throws Exception {
  statuses.add("waitingApproval");statuses.add("rejected");
  WorkflowRun b=j.buildAndAssertStatus(Result.FAILURE,pipeline(
    "stage('apply') {\n options {\n terrakubeProvision(server: 'tk-test', organizationId: 'org-1', workspaceId: 'ws-1', templateId: 'tpl-1', pollingSeconds: 5)\n }\n"+
    " steps {\n echo 'applied body'\n }\n}"));
  j.assertLogContains("Remote operation job-1 failed: rejected",b);
  j.assertLogNotContains("applied body",b);
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
