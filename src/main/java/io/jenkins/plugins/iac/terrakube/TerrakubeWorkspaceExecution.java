package io.jenkins.plugins.iac.terrakube;
import hudson.model.Run;
import io.jenkins.plugins.iac.core.AsyncBlockExecution;
import io.jenkins.plugins.iac.core.HttpJsonClient;
import io.jenkins.plugins.iac.core.IacHttpException;
import io.jenkins.plugins.iac.core.VariableMaps;
import java.io.IOException;
import java.util.*;
import org.jenkinsci.plugins.workflow.steps.StepContext;
/**
 * Finds or creates the workspace of a {@code terrakubeWorkspace} step, then lets the body run.
 * Terrakube rejects a second workspace with the same name (409), but checks this in the application, so after
 * creating one the name is listed again to catch a concurrent create. Safe to repeat after a restart: the execution
 * saves that it sent the create request, and a resumed execution that finds the workspace treats it as its own and
 * adds only the variables that are still missing.
 */
final class TerrakubeWorkspaceExecution extends AsyncBlockExecution {
 private static final long serialVersionUID=1L;
 private final String connection;
 private final TerrakubeWorkspaceSpec spec;
 private final boolean failIfExists;
 /** Set and saved before the create request. */
 private boolean createRequested;
 /** True when this execution created the workspace, so that it sets the variables. */
 private boolean created;
 private String organizationId,workspaceId;

 TerrakubeWorkspaceExecution(StepContext context,String connection,TerrakubeWorkspaceSpec spec,boolean failIfExists){
  super(context,"terrakube");this.connection=connection;this.spec=spec;this.failIfExists=failIfExists;
 }
 private String organization(){return "'"+spec.organizationName+"'";}
 private String describe(){return "workspace '"+spec.workspaceName+"' in organization "+organization();}

 @Override protected boolean advance() throws Exception {
  Run<?,?> run=getContext().get(Run.class);
  try(HttpJsonClient c=TerrakubeConnections.get().client(connection,run)){
   TerrakubeApi api=new TerrakubeApi(c);
   if(organizationId==null)organizationId=api.organizationIdByName(spec.organizationName);
   if(workspaceId==null)findOrCreate(api,run);
   if(created&&spec.hasVariables())setVariables(api,secrets(run));
   log("workspace "+workspaceId+" is ready");
   return true;
  }
 }
 private List<String> named(TerrakubeApi api) throws Exception {
  List<String> ids=api.workspaceIdsByName(organizationId,organization(),spec.workspaceName);
  if(ids.size()>1)throw new IllegalStateException(ids.size()+" Terrakube workspaces are named '"+spec.workspaceName+
    "' in organization "+organization()+" "+ids+"; delete the extra workspaces");
  return ids;
 }
 /** Sets workspaceId, and created when this execution created the workspace. */
 private void findOrCreate(TerrakubeApi api,Run<?,?> run) throws Exception {
  List<String> ids=named(api);
  if(!ids.isEmpty()&&createRequested){
   workspaceId=ids.get(0);created=true;save();
   log("found "+describe()+" created before the restart: "+workspaceId);
   return;
  }
  if(!ids.isEmpty()){keep(ids.get(0));return;}
  // Credentials are checked before the workspace exists, so that a missing one does not leave it half configured.
  secrets(run);
  createRequested=true;
  save();
  log("creating "+describe()+(spec.source==null?" for CLI-driven runs":" from "+spec.source+" branch "+spec.branchName)+variableNames());
  String id;
  try{id=api.createWorkspace(organizationId,spec.attributes(),spec.relationships());}
  catch(IacHttpException e){
   if(e.statusCode()!=409)throw e;
   ids=named(api);
   if(ids.isEmpty())throw e;
   log(describe()+" was created at the same time by someone else");
   keep(ids.get(0));
   return;
  }
  workspaceId=id;created=true;save();
  log("created workspace "+id);
  List<String> others=new ArrayList<>(api.workspaceIdsByName(organizationId,organization(),spec.workspaceName));
  others.remove(id);
  if(!others.isEmpty()){
   List<String> all=new ArrayList<>(List.of(id));all.addAll(others);
   throw new IllegalStateException("Another workspace named '"+spec.workspaceName+"' was created in organization "+organization()+
     " at the same time: "+all+"; delete the extra workspaces and serialize builds that create workspaces");
  }
 }
 private void keep(String id) throws Exception {
  if(failIfExists)throw new IllegalStateException("Terrakube "+describe()+" already exists: "+id+" (ifExists: 'fail')");
  workspaceId=id;save();
  log("found existing workspace "+id+"; configuration and variables not compared or changed");
 }
 private String variableNames(){
  StringJoiner names=new StringJoiner(", "," with ","").setEmptyValue("");
  if(!spec.variables.isEmpty())names.add("variables "+spec.variables.keySet());
  if(!spec.environmentVariables.isEmpty())names.add("environment variables "+spec.environmentVariables.keySet());
  if(!spec.sensitiveVariables.isEmpty())names.add("sensitive variables "+spec.sensitiveVariables.keySet());
  if(!spec.sensitiveEnvironmentVariables.isEmpty())names.add("sensitive environment variables "+spec.sensitiveEnvironmentVariables.keySet());
  return names.toString();
 }
 /** Values of the sensitive variables, read from their credentials now and never stored. Keys are "CATEGORY:name". */
 private Map<String,String> secrets(Run<?,?> run){
  Map<String,String> values=new HashMap<>();
  spec.sensitiveVariables.forEach((k,id)->values.put("TERRAFORM:"+k,VariableMaps.secretText(id,k,run)));
  spec.sensitiveEnvironmentVariables.forEach((k,id)->values.put("ENV:"+k,VariableMaps.secretText(id,k,run)));
  return values;
 }
 /** Creates the variables the workspace does not have yet, so that a resumed execution does not repeat any. */
 private void setVariables(TerrakubeApi api,Map<String,String> secrets) throws Exception {
  Set<String> existing=api.variableKeys(organizationId,workspaceId);
  List<String[]> wanted=new ArrayList<>();
  spec.variables.forEach((k,v)->wanted.add(new String[]{"TERRAFORM",k,v,"false"}));
  spec.environmentVariables.forEach((k,v)->wanted.add(new String[]{"ENV",k,v,"false"}));
  spec.sensitiveVariables.keySet().forEach(k->wanted.add(new String[]{"TERRAFORM",k,secrets.get("TERRAFORM:"+k),"true"}));
  spec.sensitiveEnvironmentVariables.keySet().forEach(k->wanted.add(new String[]{"ENV",k,secrets.get("ENV:"+k),"true"}));
  for(String[] v:wanted){
   if(existing.contains(v[0]+":"+v[1]))continue;
   try{api.createVariable(organizationId,workspaceId,v[0],v[1],v[2],Boolean.parseBoolean(v[3]));}
   catch(Exception e){
    throw new IOException("Terrakube workspace "+workspaceId+" was created, but its "+v[0]+" variable "+v[1]+" could not be set ("+
      e.getMessage()+"); set the missing variables in Terrakube or delete the workspace before running this stage again",e);
   }
  }
 }
}
