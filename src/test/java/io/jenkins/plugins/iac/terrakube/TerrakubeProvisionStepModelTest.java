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
  for(String name:new String[]{"organizationId","workspaceId","templateId","organizationName","workspaceName","templateName","branch"})
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
 @Test void namesRoundTrip(JenkinsRule j) throws Exception {
  TerrakubeProvisionStep step=model.instantiate(Map.of("server","tk","organizationName","acme","workspaceName","my ws","templateName","Plan & Apply"));
  assertNull(step.getWorkspaceId());
  assertEquals(Map.of("organizationName","acme","workspaceName","my ws","templateName","Plan & Apply"),step.parameters());
  Map<String,?> args=DescribableModel.uninstantiate2_(step).getArguments();
  assertEquals(Map.of("server","tk","organizationName","acme","workspaceName","my ws","templateName","Plan & Apply"),args);
 }
 @Test void snippetShowsOnlyTheConfiguredParameters(JenkinsRule j){
  TerrakubeProvisionStep step=new TerrakubeProvisionStep("tk");
  step.setOrganizationId("org-1");step.setWorkspaceName("network");step.setTemplateId("tpl-1");
  String snippet=Snippetizer.object2Groovy(step);
  for(String arg:new String[]{"organizationId: 'org-1'","workspaceName: 'network'","templateId: 'tpl-1'"})
   assertTrue(snippet.contains(arg),snippet);
  for(String arg:new String[]{"workspaceId","organizationName","templateName"})assertFalse(snippet.contains(arg),snippet);
  String ids=Snippetizer.object2Groovy(new TerrakubeProvisionStep("tk","ws-1","org-1","tpl-1"));
  for(String arg:new String[]{"organizationName","workspaceName","templateName"})assertFalse(ids.contains(arg),ids);
 }
 @Test void mixedIdsAndNamesKeepTheNamesInTheParameters(){
  TerrakubeProvisionStep step=new TerrakubeProvisionStep("tk");
  step.setOrganizationId("org-1");step.setWorkspaceName("network");step.setTemplateId("tpl-1");step.setBranch("main");
  assertEquals(Map.of("organizationId","org-1","workspaceName","network","templateId","tpl-1","branch","main"),step.parameters());
  step=new TerrakubeProvisionStep("tk");
  step.setOrganizationName("acme");step.setWorkspaceId("ws-1");step.setTemplateName("apply");
  assertEquals(Map.of("organizationName","acme","templateName","apply"),step.parameters());
 }
 @Test void eachKindNeedsItsIdOrName(){
  for(String kind:new String[]{"organization","workspace","template"}){
   TerrakubeProvisionStep step=new TerrakubeProvisionStep("tk","ws-1","org-1","tpl-1");
   switch(kind){case "organization"->step.setOrganizationId(null);case "workspace"->step.setWorkspaceId(null);default->step.setTemplateId(null);}
   assertEquals("terrakubeProvision needs "+kind+"Id or "+kind+"Name in the stage options",
     assertThrows(IllegalArgumentException.class,step::parameters).getMessage());
  }
 }
 @Test void eachKindTakesEitherIdOrName(){
  for(String kind:new String[]{"organization","workspace","template"}){
   TerrakubeProvisionStep step=new TerrakubeProvisionStep("tk","ws-1","org-1","tpl-1");
   switch(kind){case "organization"->step.setOrganizationName("acme");case "workspace"->step.setWorkspaceName("network");default->step.setTemplateName("apply");}
   assertEquals("Use either "+kind+"Id or "+kind+"Name in the terrakubeProvision stage options, not both",
     assertThrows(IllegalArgumentException.class,step::parameters).getMessage());
  }
 }
 @Test void namesAreExactAndFreeForm(){
  TerrakubeProvisionStep step=new TerrakubeProvisionStep("tk");
  for(String name:new String[]{" my ws ","o'neil","back\\slash","*prod*","a/b?c#d","é","x".repeat(255)}){
   step.setWorkspaceName(name);assertEquals(name,step.getWorkspaceName());
  }
  step.setOrganizationName(" ");step.setTemplateName("");
  assertNull(step.getOrganizationName());
  assertNull(step.getTemplateName());
  for(String bad:new String[]{"a\nb","a\tb","x\u007f","x".repeat(256)}){
   assertThrows(IllegalArgumentException.class,()->step.setOrganizationName(bad),bad);
   assertThrows(IllegalArgumentException.class,()->step.setWorkspaceName(bad),bad);
   assertThrows(IllegalArgumentException.class,()->step.setTemplateName(bad),bad);
  }
 }
 @Test void idsAreValidated(){
  TerrakubeProvisionStep step=new TerrakubeProvisionStep("tk");
  assertThrows(IllegalArgumentException.class,()->step.setOrganizationId("org/1"));
  assertThrows(IllegalArgumentException.class,()->step.setTemplateId("tpl 1"));
  assertThrows(IllegalArgumentException.class,()->step.setWorkspaceId("ws\n1"));
 }
}
