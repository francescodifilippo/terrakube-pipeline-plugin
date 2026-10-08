package io.jenkins.plugins.iac.terrakube;

import static org.junit.jupiter.api.Assertions.*;

import hudson.ExtensionList;
import io.jenkins.plugins.iac.core.IacBackend;
import java.util.Set;
import java.util.stream.Collectors;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class TerrakubePluginWiringTest {
 @Test
 void registersBackendAndPipelineSteps(JenkinsRule jenkins) {
  assertNotNull(jenkins.jenkins);
  assertEquals("terrakube",IacBackend.find("terrakube").id());
  Set<String> names=ExtensionList.lookup(StepDescriptor.class).stream()
    .map(StepDescriptor::getFunctionName).collect(Collectors.toSet());
  assertTrue(names.contains("terrakubeProvision"));
  assertTrue(names.contains("terrakubeAwait"));
  assertNotNull(TerrakubeConnections.get());
 }
}
