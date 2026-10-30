package io.jenkins.plugins.iac.terrakube;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.fasterxml.jackson.databind.JsonNode;
import hudson.model.Result;
import hudson.util.Secret;
import io.jenkins.plugins.iac.core.Connection;
import java.util.ArrayList;
import java.util.List;
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
 private JenkinsRule j;
 private TerrakubeStub terrakube;

 @BeforeEach void setUp(JenkinsRule rule) throws Exception {
  j=rule;
  terrakube=new TerrakubeStub();
  SystemCredentialsProvider.getInstance().getCredentials().add(
    new StringCredentialsImpl(CredentialsScope.GLOBAL,"terrakube-token","Terrakube token",Secret.fromString("not-a-real-token")));
  SystemCredentialsProvider.getInstance().getCredentials().add(
    new StringCredentialsImpl(CredentialsScope.GLOBAL,"db-password","DB password",Secret.fromString(SECRET_VALUE)));
  SystemCredentialsProvider.getInstance().save();
  Connection c=new Connection("tk-test",terrakube.url(),"terrakube-token");
  c.setAllowHttp(true);
  TerrakubeConnections.get().setConnections(List.of(c));
 }
 @AfterEach void tearDown(){terrakube.close();}

 @Test void createsAWorkspaceFromARepositoryWithVariables() throws Exception {
  WorkflowRun b=j.buildAndAssertSuccess(pipeline(stage("source: 'https://github.com/acme/infra.git', branchName: 'main', "+
    "workingDirectory: '/stacks/network', vcsId: 'vcs-1', iacType: 'tofu', executionMode: 'remote', description: 'Network', "+
    "variables: [region: 'eu-west-1', node_count: 3], environmentVariables: [TF_LOG: 'INFO'], "+
    "sensitiveEnvironmentVariables: [DB_PASSWORD: 'db-password']")));
  assertEquals(1,terrakube.creates.size());
  JsonNode data=terrakube.creates.get(0).path("data"),a=data.path("attributes");
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
  assertEquals(List.of("TERRAFORM:region","TERRAFORM:node_count","ENV:TF_LOG","ENV:DB_PASSWORD"),terrakube.vars.get("ws-new1"));
  assertEquals("3",terrakube.varCreates.get(1).path("data").path("attributes").path("value").asText());
  JsonNode secret=terrakube.varCreates.get(3).path("data").path("attributes");
  assertEquals(SECRET_VALUE,secret.path("value").asText());
  assertTrue(secret.path("sensitive").asBoolean());
  assertFalse(terrakube.varCreates.get(0).path("data").path("attributes").path("sensitive").asBoolean());
  j.assertLogContains("IaC terrakube: creating workspace 'network' in organization 'acme' from https://github.com/acme/infra.git branch main with "+
    "variables [region, node_count], environment variables [TF_LOG], sensitive environment variables [DB_PASSWORD]",b);
  j.assertLogContains("created workspace ws-new1",b);
  j.assertLogNotContains(SECRET_VALUE,b);
  j.assertLogNotContains("eu-west-1",b);
  j.assertLogContains("workspace body",b);
 }

 @Test void withoutASourceTheWorkspaceIsCliDriven() throws Exception {
  WorkflowRun b=j.buildAndAssertSuccess(pipeline(stage(null)));
  JsonNode data=terrakube.creates.get(0).path("data"),a=data.path("attributes");
  assertEquals("empty",a.path("source").asText());
  assertEquals("remote-content",a.path("branch").asText());
  assertEquals(List.of("name","terraformVersion","source","branch","folder"),fieldNames(a));
  assertEquals("/",a.path("folder").asText());
  assertFalse(data.has("relationships"));
  j.assertLogContains("for CLI-driven runs",b);
 }

 @Test void keepsAnExistingWorkspaceUnchanged() throws Exception {
  terrakube.workspaces.add(new String[]{"ws-existing","network"});
  WorkflowRun b=j.buildAndAssertSuccess(pipeline(stage("variables: [region: 'eu-west-1']")));
  assertEquals(0,terrakube.creates.size());
  assertEquals(0,terrakube.varCreates.size());
  j.assertLogContains("found existing workspace ws-existing; configuration and variables not compared or changed",b);
  j.assertLogContains("workspace body",b);
 }

 @Test void ifExistsFailRejectsAnExistingWorkspace() throws Exception {
  terrakube.workspaces.add(new String[]{"ws-existing","network"});
  WorkflowRun b=j.buildAndAssertStatus(Result.FAILURE,pipeline(stage("ifExists: 'fail'")));
  j.assertLogContains("Terrakube workspace 'network' in organization 'acme' already exists: ws-existing (ifExists: 'fail')",b);
  j.assertLogNotContains("workspace body",b);
 }

 @Test void severalWorkspacesWithTheNameFail() throws Exception {
  terrakube.workspaces.add(new String[]{"ws-a","network"});terrakube.workspaces.add(new String[]{"ws-b","network"});
  WorkflowRun b=j.buildAndAssertStatus(Result.FAILURE,pipeline(stage(null)));
  j.assertLogContains("2 Terrakube workspaces are named 'network' in organization 'acme' [ws-a, ws-b]",b);
  assertEquals(0,terrakube.creates.size());
 }

 @Test void aConcurrentCreateRejectedWith409KeepsTheOtherWorkspace() throws Exception {
  terrakube.createdConcurrently="network";
  WorkflowRun b=j.buildAndAssertSuccess(pipeline(stage("variables: [region: 'eu-west-1']")));
  assertEquals(1,terrakube.creates.size());
  assertEquals(0,terrakube.varCreates.size());
  j.assertLogContains("was created at the same time by someone else",b);
  j.assertLogContains("found existing workspace ws-other",b);
 }

 @Test void aConcurrentCreateThatTheCheckMissesFails() throws Exception {
  terrakube.duplicatedConcurrently="network";
  WorkflowRun b=j.buildAndAssertStatus(Result.FAILURE,pipeline(stage("variables: [region: 'eu-west-1']")));
  j.assertLogContains("Another workspace named 'network' was created in organization 'acme' at the same time: [ws-new1, ws-twin]",b);
  assertEquals(0,terrakube.varCreates.size());
  j.assertLogNotContains("workspace body",b);
 }

 @Test void aMissingCredentialFailsBeforeCreating() throws Exception {
  WorkflowRun b=j.buildAndAssertStatus(Result.FAILURE,pipeline(stage("sensitiveVariables: [token: 'no-such-credential']")));
  j.assertLogContains("Missing or inaccessible Secret text credential no-such-credential for sensitive variable token",b);
  assertEquals(0,terrakube.creates.size());
 }

 @Test void aFailedVariableNamesTheWorkspace() throws Exception {
  terrakube.failingVariable="node_count";
  WorkflowRun b=j.buildAndAssertStatus(Result.FAILURE,pipeline(stage("variables: [region: 'eu-west-1', node_count: 3]")));
  j.assertLogContains("Terrakube workspace ws-new1 was created, but its TERRAFORM variable node_count could not be set",b);
  j.assertLogNotContains("workspace body",b);
 }

 @Test void aCreatedWorkspaceCanBeUsedByNameInALaterStage() throws Exception {
  j.buildAndAssertSuccess(pipeline(stage(null)+
    "stage('plan') {\n options { terrakubeProvision(connection: 'tk-test', organizationName: 'acme', workspaceName: 'network', "+
    "templateName: 'plan') }\n steps { echo 'plan body' }\n}\n"));
  assertEquals(1,terrakube.jobs.size());
  assertTrue(terrakube.jobs.get(0).contains("\"id\":\"ws-new1\""),terrakube.jobs.get(0));
 }

 @Test void terraformVersionIsRequired() throws Exception {
  WorkflowRun b=j.buildAndAssertStatus(Result.FAILURE,pipeline(
    "stage('workspace') {\n options { terrakubeWorkspace(connection: 'tk-test', organizationName: 'acme', workspaceName: 'network') }\n"+
    " steps { echo 'workspace body' }\n}\n"));
  j.assertLogContains("terraformVersion is required in the terrakubeWorkspace stage options",b);
  assertEquals(0,terrakube.creates.size());
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
  assertEquals(0,terrakube.creates.size());
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
}
