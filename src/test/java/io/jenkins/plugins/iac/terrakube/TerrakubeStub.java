package io.jenkins.plugins.iac.terrakube;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.jenkins.plugins.iac.core.HttpJsonClient;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** In-memory stub of the Terrakube organization, workspace, variable, template and job API for organization acme. */
final class TerrakubeStub implements AutoCloseable {
 static final String WORKSPACES="/api/v1/organization/org-1/workspace";
 private final HttpServer server;
 private final ExecutorService executor=Executors.newCachedThreadPool();
 /** Workspaces of organization acme (org-1) as [id, name], in creation order; names need not be unique. */
 final List<String[]> workspaces=new CopyOnWriteArrayList<>();
 /** Variables by workspace ID, as "CATEGORY:key". */
 final Map<String,List<String>> vars=new ConcurrentHashMap<>();
 final List<JsonNode> creates=new CopyOnWriteArrayList<>(),varCreates=new CopyOnWriteArrayList<>();
 final List<String> jobs=new CopyOnWriteArrayList<>();
 /** When set, the next create answers 409, as if another build had just created this workspace. */
 volatile String createdConcurrently;
 /** When set, a create succeeds and another workspace with this name appears too, as a race the 409 check misses. */
 volatile String duplicatedConcurrently;
 /** Variable key whose create answers 400. */
 volatile String failingVariable;
 /** When set, the next workspace create is stored and then not answered until {@link #close}. */
 volatile boolean holdCreate;
 /** Variable key whose create is stored and then not answered until {@link #close}. */
 volatile String holdVariable;
 /** Counted down when a request is held. */
 final CountDownLatch held=new CountDownLatch(1);
 private final CountDownLatch released=new CountDownLatch(1);

 TerrakubeStub() throws IOException {
  server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  // Several threads, so that a held request does not block the others.
  server.setExecutor(executor);
  server.createContext("/",this::handle);
  server.start();
 }
 String url(){return "http://127.0.0.1:"+server.getAddress().getPort();}
 @Override public void close(){released.countDown();server.stop(0);executor.shutdownNow();}

 private void handle(HttpExchange exchange) throws IOException {
  String path=exchange.getRequestURI().getRawPath(),method=exchange.getRequestMethod();
  if(path.equals("/api/v1/organization")){
   respond(exchange,200,"{\"data\":[{\"id\":\"org-1\",\"type\":\"organization\",\"attributes\":{\"name\":\"acme\"}}]}");
  }else if(path.equals(WORKSPACES)&&"POST".equals(method)){
   JsonNode body=HttpJsonClient.JSON.readTree(exchange.getRequestBody());
   creates.add(body);
   String name=body.path("data").path("attributes").path("name").asText();
   if(name.equals(createdConcurrently)){workspaces.add(new String[]{"ws-other",name});createdConcurrently=null;}
   if(workspaces.stream().anyMatch(w->w[1].equals(name))){respond(exchange,409,"{\"errors\":[{\"detail\":\"exists\"}]}");return;}
   String id="ws-new"+(workspaces.size()+1);
   workspaces.add(new String[]{id,name});
   if(name.equals(duplicatedConcurrently))workspaces.add(new String[]{"ws-twin",name});
   if(holdCreate){holdCreate=false;hold();}
   respond(exchange,201,"{\"data\":{\"id\":\""+id+"\",\"type\":\"workspace\",\"attributes\":{\"name\":\""+name+"\"}}}");
  }else if(path.equals(WORKSPACES)){
   StringBuilder b=new StringBuilder("{\"data\":[");
   for(int i=0;i<workspaces.size();i++)b.append(i==0?"":",").append("{\"id\":\"").append(workspaces.get(i)[0])
     .append("\",\"type\":\"workspace\",\"attributes\":{\"name\":\"").append(workspaces.get(i)[1]).append("\",\"deleted\":false}}");
   respond(exchange,200,b.append("]}").toString());
  }else if(path.startsWith(WORKSPACES+"/")&&path.endsWith("/variable")){
   String id=path.split("/")[6];
   List<String> list=vars.computeIfAbsent(id,k->new CopyOnWriteArrayList<>());
   if("POST".equals(method)){
    JsonNode body=HttpJsonClient.JSON.readTree(exchange.getRequestBody());
    varCreates.add(body);
    JsonNode a=body.path("data").path("attributes");
    if(a.path("key").asText().equals(failingVariable)){respond(exchange,400,"{\"errors\":[{\"detail\":\"bad\"}]}");return;}
    list.add(a.path("category").asText()+":"+a.path("key").asText());
    if(a.path("key").asText().equals(holdVariable)){holdVariable=null;hold();}
    respond(exchange,201,"{\"data\":{\"id\":\"var-"+list.size()+"\",\"type\":\"variable\"}}");
    return;
   }
   StringBuilder b=new StringBuilder("{\"data\":[");
   for(int i=0;i<list.size();i++){
    String[] v=list.get(i).split(":",2);
    b.append(i==0?"":",").append("{\"type\":\"variable\",\"attributes\":{\"category\":\"").append(v[0]).append("\",\"key\":\"").append(v[1]).append("\"}}");
   }
   respond(exchange,200,b.append("]}").toString());
  }else if(path.equals("/api/v1/organization/org-1/template")){
   respond(exchange,200,"{\"data\":[{\"id\":\"tpl-1\",\"type\":\"template\",\"attributes\":{\"name\":\"plan\"}}]}");
  }else if(path.equals("/api/v1/organization/org-1/job")){
   jobs.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
   respond(exchange,201,"{\"data\":{\"id\":\"job-1\",\"attributes\":{\"status\":\"completed\"}}}");
  }else respond(exchange,404,"{\"errors\":[]}");
 }

 /** Keeps the request open, as if the response were lost, until the stub closes. */
 private void hold(){
  held.countDown();
  try{released.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}
 }
 private static void respond(HttpExchange exchange,int code,String body) throws IOException {
  byte[] b=body.getBytes(StandardCharsets.UTF_8);
  exchange.getResponseHeaders().set("Content-Type","application/vnd.api+json");
  exchange.sendResponseHeaders(code,b.length);
  try(OutputStream o=exchange.getResponseBody()){o.write(b);}
 }
}
