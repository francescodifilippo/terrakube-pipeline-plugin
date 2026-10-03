package io.jenkins.plugins.iac.terrakube;
import hudson.Extension;
import io.jenkins.plugins.iac.core.AbstractIacConnections;
import hudson.ExtensionList;
@Extension public final class TerrakubeConnections extends AbstractIacConnections {
 @Override public String getDisplayName(){return "Terrakube connections";}
 public static TerrakubeConnections get(){return ExtensionList.lookupSingleton(TerrakubeConnections.class);}
}
