package com.edem.dedup4j.management;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dedup4j.dashboard-registration")
public class DashboardRegistrationProperties {

    private boolean enabled;
    private String dashboardUrl;
    private String instanceName = "dedup4j";
    private String advertisedUrl;
    private String instanceId;

    public DashboardRegistrationProperties() {
    }

    public DashboardRegistrationProperties(
            boolean enabled, String dashboardUrl, String instanceName, String advertisedUrl, String instanceId) {
        this.enabled = enabled;
        this.dashboardUrl = dashboardUrl;
        this.instanceName = instanceName;
        this.advertisedUrl = advertisedUrl;
        this.instanceId = instanceId;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getDashboardUrl() { return dashboardUrl; }
    public void setDashboardUrl(String dashboardUrl) { this.dashboardUrl = dashboardUrl; }
    public String getInstanceName() { return instanceName; }
    public void setInstanceName(String instanceName) { this.instanceName = instanceName; }
    public String getAdvertisedUrl() { return advertisedUrl; }
    public void setAdvertisedUrl(String advertisedUrl) { this.advertisedUrl = advertisedUrl; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
}
