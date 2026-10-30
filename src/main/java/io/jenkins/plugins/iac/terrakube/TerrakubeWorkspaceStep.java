package io.jenkins.plugins.iac.terrakube;
import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.jenkins.plugins.iac.core.Identifiers;
import io.jenkins.plugins.iac.core.VariableMaps;
import java.util.*;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
/**
 * Declarative stage option: options { terrakubeWorkspace(...) }. Finds the workspace named {@code workspaceName} in
 * the organization named {@code organizationName}, or creates it with its variables, then runs the stage. It runs no
 * job: later stages use {@code terrakubeProvision(organizationName:, workspaceName:, ...)}.
 */
public final class TerrakubeWorkspaceStep extends Step {
 static final Set<String> IF_EXISTS=Set.of("keep","fail"),IAC_TYPES=Set.of("terraform","tofu"),EXECUTION_MODES=Set.of("remote","local");
 private final String connection,organizationName,workspaceName;
 private String description,terraformVersion,iacType,executionMode,ifExists="keep";
 private String source,branchName,workingDirectory,vcsId;
 private LinkedHashMap<String,String> variables=new LinkedHashMap<>(),environmentVariables=new LinkedHashMap<>();
 private LinkedHashMap<String,String> sensitiveVariables=new LinkedHashMap<>(),sensitiveEnvironmentVariables=new LinkedHashMap<>();

 @DataBoundConstructor public TerrakubeWorkspaceStep(String connection,String organizationName,String workspaceName){
  this.connection=Identifiers.required(connection,"connection");
  this.organizationName=TerrakubeProvisionStep.name(organizationName,"organizationName");
  if(this.organizationName==null)throw new IllegalArgumentException("organizationName is required in the terrakubeWorkspace stage options");
  this.workspaceName=TerrakubeProvisionStep.name(workspaceName,"workspaceName");
  if(this.workspaceName==null)throw new IllegalArgumentException("workspaceName is required in the terrakubeWorkspace stage options");
  if(this.workspaceName.length()>128)throw new IllegalArgumentException("workspaceName must be 128 characters or fewer");
 }
 public String getConnection(){return connection;}
 public String getOrganizationName(){return organizationName;}
 public String getWorkspaceName(){return workspaceName;}
 public String getDescription(){return description;}
 public String getTerraformVersion(){return terraformVersion;}
 public String getIacType(){return iacType;}
 public String getExecutionMode(){return executionMode;}
 public String getIfExists(){return ifExists;}
 public String getSource(){return source;}
 public String getBranchName(){return branchName;}
 public String getWorkingDirectory(){return workingDirectory;}
 public String getVcsId(){return vcsId;}
 public Map<String,String> getVariables(){return VariableMaps.view(variables);}
 public Map<String,String> getEnvironmentVariables(){return VariableMaps.view(environmentVariables);}
 public Map<String,String> getSensitiveVariables(){return VariableMaps.view(sensitiveVariables);}
 public Map<String,String> getSensitiveEnvironmentVariables(){return VariableMaps.view(sensitiveEnvironmentVariables);}

 @DataBoundSetter public void setDescription(String v){description=text(v,"description",4096);}
 @DataBoundSetter public void setTerraformVersion(String v){terraformVersion=text(v,"terraformVersion",64);}
 @DataBoundSetter public void setIacType(String v){
  if(!IAC_TYPES.contains(v))throw new IllegalArgumentException("iacType must be terraform or tofu");
  iacType=v;
 }
 @DataBoundSetter public void setExecutionMode(String v){
  if(!EXECUTION_MODES.contains(v))throw new IllegalArgumentException("executionMode must be remote or local");
  executionMode=v;
 }
 @DataBoundSetter public void setIfExists(String v){
  if(!IF_EXISTS.contains(v))throw new IllegalArgumentException("ifExists must be keep or fail");
  ifExists=v;
 }
 @DataBoundSetter public void setSource(String v){source=text(v,"source",4096);}
 @DataBoundSetter public void setBranchName(String v){branchName=v==null||v.isBlank()?null:TerrakubeApi.validBranch(v);}
 @DataBoundSetter public void setWorkingDirectory(String v){workingDirectory=text(v,"workingDirectory",4096);}
 @DataBoundSetter public void setVcsId(String v){vcsId=Identifiers.optional(v,"vcsId");}
 /** Pipeline passes a map literal's values as Groovy gives them (numbers, booleans, GStrings): each becomes a string. */
 @DataBoundSetter public void setVariables(Map<?,?> v){variables=VariableMaps.values(v,"variables");}
 @DataBoundSetter public void setEnvironmentVariables(Map<?,?> v){environmentVariables=VariableMaps.values(v,"environmentVariables");}
 @DataBoundSetter public void setSensitiveVariables(Map<?,?> v){sensitiveVariables=VariableMaps.credentialIds(v,"sensitiveVariables");}
 @DataBoundSetter public void setSensitiveEnvironmentVariables(Map<?,?> v){
  sensitiveEnvironmentVariables=VariableMaps.credentialIds(v,"sensitiveEnvironmentVariables");
 }

 /** Checks option combinations; runs when the stage starts, before any Terrakube call. */
 TerrakubeWorkspaceSpec spec(){
  if(terraformVersion==null)
   throw new IllegalArgumentException("terraformVersion is required in the terrakubeWorkspace stage options; Terrakube runs jobs with that version");
  if(source==null&&(branchName!=null||workingDirectory!=null||vcsId!=null))
   throw new IllegalArgumentException("branchName, workingDirectory and vcsId need source (the repository URL) in the terrakubeWorkspace stage options");
  if(source!=null&&branchName==null)
   throw new IllegalArgumentException("source needs branchName in the terrakubeWorkspace stage options");
  VariableMaps.disjoint(variables,sensitiveVariables,"variables","sensitiveVariables");
  VariableMaps.disjoint(environmentVariables,sensitiveEnvironmentVariables,"environmentVariables","sensitiveEnvironmentVariables");
  return new TerrakubeWorkspaceSpec(organizationName,workspaceName,description,terraformVersion,iacType,executionMode,
    source,branchName,workingDirectory,vcsId,variables,environmentVariables,sensitiveVariables,sensitiveEnvironmentVariables);
 }
 @Override public StepExecution start(StepContext context){
  return new TerrakubeWorkspaceExecution(context,connection,spec(),"fail".equals(ifExists));
 }
 private static String text(String value,String name,int max){
  if(value==null||value.isBlank())return null;
  if(value.length()>max||value.chars().anyMatch(c->c<32||c==127))
   throw new IllegalArgumentException(name+" must be 1.."+max+" characters without control characters");
  return value;
 }
 @Extension public static final class DescriptorImpl extends StepDescriptor {
  @Override public String getFunctionName(){return "terrakubeWorkspace";}
  @Override public String getDisplayName(){return "Find or create a Terrakube workspace (Declarative stage option)";}
  @Override public boolean takesImplicitBlockArgument(){return true;}
  @Override public Set<? extends Class<?>> getRequiredContext(){return Set.of(Run.class,TaskListener.class);}
 }
}
