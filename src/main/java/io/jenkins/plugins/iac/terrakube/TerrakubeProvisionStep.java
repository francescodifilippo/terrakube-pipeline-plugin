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
/**
 * Declarative stage option: options { terrakubeProvision(...) }. The organization, workspace and template are each
 * given by ID or by exact name; {@link TerrakubeBackend#resolve} looks names up when the stage runs.
 */
public final class TerrakubeProvisionStep extends AbstractProvisionStep {
 private String organizationId,templateId,branch,organizationName,workspaceName,templateName;
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
 public String getOrganizationName(){return organizationName;}
 public String getWorkspaceName(){return workspaceName;}
 public String getTemplateName(){return templateName;}
 @DataBoundSetter public void setWorkspaceId(String value){setTargetId(value);}
 @DataBoundSetter public void setOrganizationId(String value){organizationId=Identifiers.optional(value,"organizationId");}
 @DataBoundSetter public void setTemplateId(String value){templateId=Identifiers.optional(value,"templateId");}
 @DataBoundSetter public void setOrganizationName(String value){organizationName=name(value,"organizationName");}
 @DataBoundSetter public void setWorkspaceName(String value){workspaceName=name(value,"workspaceName");}
 @DataBoundSetter public void setTemplateName(String value){templateName=name(value,"templateName");}
 @DataBoundSetter public void setBranch(String value){branch = value == null || value.isBlank() ? null : TerrakubeApi.validBranch(value);}
 @Override protected String provider(){return "terrakube";}
 @Override protected Map<String,String> parameters(){
  String organization=oneOf("organization",organizationId,organizationName),workspace=oneOf("workspace",getTargetId(),workspaceName),
    template=oneOf("template",templateId,templateName);
  Map<String,String> opts=new LinkedHashMap<>();opts.put(organization,organizationId!=null?organizationId:organizationName);
  if(workspaceName!=null)opts.put(workspace,workspaceName);
  opts.put(template,templateId!=null?templateId:templateName);
  if(branch!=null)opts.put("branch",branch);return opts;
 }
 /** Checks that exactly one of {@code <kind>Id} and {@code <kind>Name} is set, and returns the parameter name in use. */
 private static String oneOf(String kind,String id,String name){
  if(id!=null&&name!=null)
   throw new IllegalArgumentException("Use either "+kind+"Id or "+kind+"Name in the terrakubeProvision stage options, not both");
  if(id==null&&name==null)
   throw new IllegalArgumentException("terrakubeProvision needs "+kind+"Id or "+kind+"Name in the stage options");
  return kind+(id!=null?"Id":"Name");
 }
 /** Null for null/blank; otherwise the exact name, 1..255 characters without control characters. */
 private static String name(String value,String name){
  if(value==null||value.isBlank())return null;
  if(value.length()>255||value.chars().anyMatch(c->c<32||c==127))
   throw new IllegalArgumentException(name+" must contain 1..255 characters without control characters");
  return value;
 }
 @Extension public static final class DescriptorImpl extends StepDescriptor {
  @Override public String getFunctionName(){return "terrakubeProvision";}
  @Override public String getDisplayName(){return "Terrakube job (Declarative stage option)";}
  @Override public boolean takesImplicitBlockArgument(){return true;}
  @Override public Set<? extends Class<?>> getRequiredContext(){return Set.of(Run.class,TaskListener.class);}
 }
}
