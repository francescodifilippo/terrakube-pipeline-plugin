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
 private String organizationId,templateId,branch;
 /** The IDs are optional setters so that Pipeline Syntax does not list them as required. */
 @DataBoundConstructor public TerrakubeProvisionStep(String server){super(server);}
 /** Convenience for Java callers; Pipeline binds the IDs through their setters. */
 public TerrakubeProvisionStep(String server,String workspaceId,String organizationId,String templateId){
  super(server,workspaceId);setOrganizationId(organizationId);setTemplateId(templateId);
 }
 public String getServer(){return getConnectionId();}
 public String getWorkspaceId(){return getTargetId();}
 public String getOrganizationId(){return organizationId;}
 public String getTemplateId(){return templateId;}
 public String getBranch(){return branch;}
 @DataBoundSetter public void setWorkspaceId(String value){setTargetId(value);}
 @DataBoundSetter public void setOrganizationId(String value){organizationId=Identifiers.optional(value,"organizationId");}
 @DataBoundSetter public void setTemplateId(String value){templateId=Identifiers.optional(value,"templateId");}
 @DataBoundSetter public void setBranch(String value){branch = value == null || value.isBlank() ? null : TerrakubeApi.validBranch(value);}
 @Override protected String provider(){return "terrakube";}
 @Override protected Map<String,String> parameters(){
  for(String[] id:new String[][]{{"organizationId",organizationId},{"workspaceId",getTargetId()},{"templateId",templateId}})
   if(id[1]==null)throw new IllegalArgumentException("terrakubeProvision needs "+id[0]+" in the stage options");
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
