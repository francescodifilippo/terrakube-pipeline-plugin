package io.jenkins.plugins.iac.terrakube;
import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.jenkins.plugins.iac.core.AbstractProvisionStep;
import io.jenkins.plugins.iac.core.Identifiers;
import java.util.*;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
/** Declarative stage option: options { terrakubeProvision(...) } */
public final class TerrakubeProvisionStep extends AbstractProvisionStep {
 private final String organizationId,templateId;
 private String branch;
 @DataBoundConstructor public TerrakubeProvisionStep(String server,String workspaceId,String organizationId,String templateId){
  super(server,workspaceId);this.organizationId=Identifiers.required(organizationId,"organizationId");
  this.templateId=Identifiers.required(templateId,"templateId");
 }
 public String getOrganizationId(){return organizationId;}
 public String getTemplateId(){return templateId;}
 public String getBranch(){return branch;}
 @DataBoundSetter public void setBranch(String value){branch = value == null || value.isBlank() ? null : validBranch(value);}
 private static String validBranch(String value) {
  if (value.length() > 256 || value.chars().anyMatch(c -> c < 32 || c == 127))
   throw new IllegalArgumentException("branch must be 1..256 characters without control characters");
  return value;
 }
 @Override protected String provider(){return "terrakube";}
 @Override protected Map<String,String> parameters(){
  Map<String,String> opts=new LinkedHashMap<>();opts.put("organizationId",organizationId);opts.put("templateId",templateId);
  if(branch!=null)opts.put("branch",branch);return opts;
 }
 @Extension public static final class DescriptorImpl extends StepDescriptor {
  @Override public String getFunctionName(){return "terrakubeProvision";}
  @Override public String getDisplayName(){return "Terrakube job (Declarative stage option)";}
  @Override public boolean takesImplicitBlockArgument(){return true;}
  @Override public Set<? extends Class<?>> getRequiredContext(){return Set.of(Run.class,TaskListener.class);}
 }
}
