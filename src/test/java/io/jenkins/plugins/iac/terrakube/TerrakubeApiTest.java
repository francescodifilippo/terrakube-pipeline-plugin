package io.jenkins.plugins.iac.terrakube;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;
import io.jenkins.plugins.iac.core.HttpJsonClient;
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
   assertTrue(request.get().contains("\"overrideBranch\":\"feature/network\""));
  } finally {stub.stop(0);}
 }
}
