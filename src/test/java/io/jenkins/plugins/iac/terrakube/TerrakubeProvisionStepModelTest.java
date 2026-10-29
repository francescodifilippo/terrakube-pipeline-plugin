package io.jenkins.plugins.iac.terrakube;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.jenkinsci.plugins.structs.describable.DescribableModel;
import org.jenkinsci.plugins.workflow.cps.Snippetizer;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** How Pipeline (and the Snippet Generator) sees the step's parameters. */
@WithJenkins
class TerrakubeProvisionStepModelTest {
 private final DescribableModel<TerrakubeProvisionStep> model=DescribableModel.of(TerrakubeProvisionStep.class);

 @Test void onlyServerIsRequired(){
  assertTrue(model.getParameter("server").isRequired());
  for(String name:new String[]{"organizationId","workspaceId","templateId","branch"})
   assertFalse(model.getParameter(name).isRequired(),name);
 }
 @Test void idsRoundTrip(JenkinsRule j) throws Exception {
  TerrakubeProvisionStep step=model.instantiate(Map.of("server","tk","organizationId","org-1","workspaceId","ws-1","templateId","tpl-1","branch","main"));
  assertEquals("ws-1",step.getWorkspaceId());
  Map<String,?> args=DescribableModel.uninstantiate2_(step).getArguments();
  assertEquals(Map.of("server","tk","organizationId","org-1","workspaceId","ws-1","templateId","tpl-1","branch","main"),args);
 }
 @Test void snippetShowsTheIds(JenkinsRule j){
  String snippet=Snippetizer.object2Groovy(new TerrakubeProvisionStep("tk","ws-1","org-1","tpl-1"));
  assertTrue(snippet.startsWith("terrakubeProvision("),snippet);
  for(String arg:new String[]{"server: 'tk'","organizationId: 'org-1'","workspaceId: 'ws-1'","templateId: 'tpl-1'"})
   assertTrue(snippet.contains(arg),snippet);
 }
 @Test void idsKeepTheirParametersAndDefaultKey(){
  TerrakubeProvisionStep step=new TerrakubeProvisionStep("tk","ws-1","org-1","tpl-1");step.setBranch("main");
  assertEquals(Map.of("organizationId","org-1","templateId","tpl-1","branch","main"),step.parameters());
 }
 @Test void eachIdIsRequiredWhenTheStepStarts(){
  assertEquals("terrakubeProvision needs organizationId in the stage options",
    assertThrows(IllegalArgumentException.class,()->new TerrakubeProvisionStep("tk","ws-1",null,"tpl-1").parameters()).getMessage());
  assertEquals("terrakubeProvision needs workspaceId in the stage options",
    assertThrows(IllegalArgumentException.class,()->new TerrakubeProvisionStep("tk",null,"org-1","tpl-1").parameters()).getMessage());
  assertEquals("terrakubeProvision needs templateId in the stage options",
    assertThrows(IllegalArgumentException.class,()->new TerrakubeProvisionStep("tk","ws-1","org-1"," ").parameters()).getMessage());
 }
 @Test void idsAreValidated(){
  TerrakubeProvisionStep step=new TerrakubeProvisionStep("tk");
  assertThrows(IllegalArgumentException.class,()->step.setOrganizationId("org/1"));
  assertThrows(IllegalArgumentException.class,()->step.setTemplateId("tpl 1"));
  assertThrows(IllegalArgumentException.class,()->step.setWorkspaceId("ws\n1"));
 }
}
