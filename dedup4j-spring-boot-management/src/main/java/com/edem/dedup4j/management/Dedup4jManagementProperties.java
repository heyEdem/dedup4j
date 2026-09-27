package com.edem.dedup4j.management;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.UUID;

@ConfigurationProperties(prefix = "dedup4j.management")
public class Dedup4jManagementProperties {

    private boolean enabled;
    private String basePath = "/dedup4j/management";
    private String instanceId = UUID.randomUUID().toString();
    private String instanceName = "dedup4j";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBasePath() { return basePath; }
    public void setBasePath(String basePath) { this.basePath = basePath; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public String getInstanceName() { return instanceName; }
    public void setInstanceName(String instanceName) { this.instanceName = instanceName; }
}
