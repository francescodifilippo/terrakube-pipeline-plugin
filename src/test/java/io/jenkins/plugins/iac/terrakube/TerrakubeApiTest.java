package io.jenkins.plugins.iac.terrakube;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.sun.net.httpserver.HttpServer;
import io.jenkins.plugins.iac.core.HttpJsonClient;
import io.jenkins.plugins.iac.core.JobResult;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
class TerrakubeApiTest {
 @Test void submitsTemplateJobWithBranch() throws Exception {
  HttpServer stub=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  AtomicReference<String> request=new AtomicReference<>();
  stub.createContext("/api/v1/organization/org-1/job", exchange -> {
   request.set(exchange.getRequestMethod()+" "+exchange.getRequestURI()+" "+new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
   byte[] body="{\"data\":{\"id\":\"job-1\",\"attributes\":{\"status\":\"pending\"}}}".getBytes(StandardCharsets.UTF_8);
   exchange.getResponseHeaders().set("Content-Type","application/vnd.api+json");
   exchange.sendResponseHeaders(201,body.length);try(OutputStream o=exchange.getResponseBody()){o.write(body);}
  });
  stub.start();
  try(HttpJsonClient client=new HttpJsonClient("http://127.0.0.1:"+stub.getAddress().getPort(),"unit-test-token",true)) {
   assertEquals("job-1",new TerrakubeApi(client).submit("org-1","ws-1","tpl-1","feature/network").id());
   assertTrue(request.get().contains("POST /api/v1/organization/org-1/job"));
   assertTrue(request.get().contains("\"type\":\"job\""));
   assertTrue(request.get().contains("\"templateReference\":\"tpl-1\""));
   assertTrue(request.get().contains("\"overrideBranch\":\"feature/network\""));
   assertTrue(request.get().contains("\"workspace\":{\"data\":{\"type\":\"workspace\",\"id\":\"ws-1\"}}"));
  } finally {stub.stop(0);}
 }

 /** Every io.terrakube.api.rs.job.JobStatus value, as the Terrakube API serializes it. */
 @ParameterizedTest @CsvSource({
  "pending,false,false,false","waitingApproval,false,false,true","approved,false,false,false",
  "queue,false,false,false","running,false,false,false","completed,true,true,false","noChanges,true,true,false",
  "notExecuted,false,false,false","rejected,true,false,false","cancelled,true,false,false","failed,true,false,false",
  "unknown,false,false,false","NeverExecuted,false,false,false"})
 void mapsTerrakubeJobStatuses(String status,boolean completed,boolean successful,boolean needsApproval) throws Exception {
  HttpServer stub=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  stub.createContext("/api/v1/organization/org-1/job/job-1", exchange -> {
   byte[] body=("{\"data\":{\"id\":\"job-1\",\"type\":\"job\",\"attributes\":{\"status\":\""+status+"\"}}}").getBytes(StandardCharsets.UTF_8);
   exchange.getResponseHeaders().set("Content-Type","application/vnd.api+json");
   exchange.sendResponseHeaders(200,body.length);try(OutputStream o=exchange.getResponseBody()){o.write(body);}
  });
  stub.start();
  try(HttpJsonClient client=new HttpJsonClient("http://127.0.0.1:"+stub.getAddress().getPort(),"unit-test-token",true)) {
   JobResult r=new TerrakubeApi(client).status("org-1","job-1");
   assertEquals(completed,r.completed(),status);
   assertEquals(successful,r.successful(),status);
   assertEquals(needsApproval,r.needsApproval(),status);
  } finally {stub.stop(0);}
 }
}
