package io.jenkins.plugins.iac.terrakube;
import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.jenkins.plugins.iac.core.AbstractAwaitStep;
import java.util.*;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;
/** Declarative stage option: options { terrakubeAwait(...) } */
public final class TerrakubeAwaitStep extends AbstractAwaitStep {
 @DataBoundConstructor public TerrakubeAwaitStep(String server,String operationKey){super(server,operationKey);}
 @Override protected String provider(){return "terrakube";}
 @Extension public static final class DescriptorImpl extends StepDescriptor {
  @Override public String getFunctionName(){return "terrakubeAwait";}
  @Override public String getDisplayName(){return "Await previous Terrakube job (Declarative stage option)";}
  @Override public boolean takesImplicitBlockArgument(){return true;}
  @Override public Set<? extends Class<?>> getRequiredContext(){return Set.of(Run.class,TaskListener.class);}
 }
}
