package io.jenkins.plugins.iac.terrakube;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.util.Secret;
import io.jenkins.plugins.iac.core.Connection;
import java.util.List;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Terrakube connections form in Manage Jenkins, System. */
@WithJenkins
class TerrakubeConnectionsTest {
 private static final String URL="https://terrakube.example.org";
 private JenkinsRule j;

 @BeforeEach void setUp(JenkinsRule rule) throws Exception {
  j=rule;
  SystemCredentialsProvider.getInstance().getCredentials().add(
    new StringCredentialsImpl(CredentialsScope.GLOBAL,"terrakube-token","Terrakube token",Secret.fromString("not-a-real-token")));
  SystemCredentialsProvider.getInstance().save();
 }

 @Test void roundtripsConnection() throws Exception {
  TerrakubeConnections.get().setServers(List.of(new Connection("tk-prod",URL,"terrakube-token")));
  HtmlPage page=j.createWebClient().goTo("configure");
  assertNotNull(page.querySelector("select.credentials-select[name='_.credentialsId']"),"credentials select rendered");
  j.configRoundtrip();
  List<Connection> servers=TerrakubeConnections.get().getServers();
  assertEquals(1,servers.size());
  assertEquals("tk-prod",servers.get(0).getName());
  assertEquals(URL,servers.get(0).getUrl());
  assertEquals("terrakube-token",servers.get(0).getCredentialsId());
  assertFalse(servers.get(0).isAllowHttp());
 }

 @Test void deletingTheLastConnectionClearsTheList() throws Exception {
  TerrakubeConnections.get().setServers(List.of(new Connection("tk-prod",URL,"terrakube-token")));
  HtmlForm form=j.createWebClient().goTo("configure").getFormByName("config");
  DomNode chunk=form.querySelector("input[name='_.url'][value='"+URL+"']");
  while(chunk!=null && !(chunk instanceof DomElement e && e.getAttribute("class").contains("repeated-chunk")))chunk=chunk.getParentNode();
  assertNotNull(chunk,"connection entry rendered");
  chunk.remove();
  j.submit(form);
  assertEquals(List.of(),TerrakubeConnections.get().getServers());
 }
}
