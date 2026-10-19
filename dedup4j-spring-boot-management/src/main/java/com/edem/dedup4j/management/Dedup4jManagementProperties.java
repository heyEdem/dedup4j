package com.edem.dedup4j.management;

import com.edem.dedup4j.history.DefaultInstanceId;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dedup4j.management")
public class Dedup4jManagementProperties {

    private boolean enabled;
    private String basePath = "/dedup4j/management";
    /** Defaults to the id history rows use when no instance id is configured. */
    private String instanceId = DefaultInstanceId.value();
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
