package io.jenkins.plugins.iac.terrakube;

import static org.junit.jupiter.api.Assertions.*;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jenkinsci.plugins.structs.describable.DescribableModel;
import org.jenkinsci.plugins.workflow.cps.Snippetizer;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** How Pipeline (and the Snippet Generator) sees the terrakubeWorkspace parameters. */
@WithJenkins
class TerrakubeWorkspaceStepModelTest {
 private final DescribableModel<TerrakubeWorkspaceStep> model=DescribableModel.of(TerrakubeWorkspaceStep.class);

 @Test void connectionOrganizationAndWorkspaceAreRequired(){
  for(String name:new String[]{"connection","organizationName","workspaceName"})assertTrue(model.getParameter(name).isRequired(),name);
  for(String name:new String[]{"terraformVersion","source","variables","sensitiveVariables","ifExists"})
   assertFalse(model.getParameter(name).isRequired(),name);
 }
 @Test void snippetShowsTheConfiguredParameters(JenkinsRule j){
  TerrakubeWorkspaceStep step=new TerrakubeWorkspaceStep("tk","acme","network");
  step.setTerraformVersion("1.9.5");
  Map<String,Object> variables=new LinkedHashMap<>();variables.put("region","eu-west-1");variables.put("count",3);
  step.setVariables(variables);
  step.setSensitiveEnvironmentVariables(Map.of("DB_PASSWORD","db-password"));
  String snippet=Snippetizer.object2Groovy(step);
  assertTrue(snippet.startsWith("terrakubeWorkspace(connection: 'tk', organizationName: 'acme', "+
    "sensitiveEnvironmentVariables: [DB_PASSWORD: 'db-password'], terraformVersion: '1.9.5', "+
    "variables: [region: 'eu-west-1', count: '3'], workspaceName: 'network')"),snippet);
 }
 @Test void namesAreChecked(){
  assertEquals("organizationName is required in the terrakubeWorkspace stage options",
    assertThrows(IllegalArgumentException.class,()->new TerrakubeWorkspaceStep("tk"," ","network")).getMessage());
  assertEquals("workspaceName must be 128 characters or fewer",
    assertThrows(IllegalArgumentException.class,()->new TerrakubeWorkspaceStep("tk","acme","w".repeat(129))).getMessage());
 }
}
