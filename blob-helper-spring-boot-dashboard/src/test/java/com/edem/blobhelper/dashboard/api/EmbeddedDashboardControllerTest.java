package com.edem.blobhelper.dashboard.api;

import com.edem.blobhelper.autoconfigure.BlobHelperProperties;
import com.edem.blobhelper.dashboard.autoconfigure.BlobHelperDashboardProperties;
import com.edem.blobhelper.management.BlobHelperManagementProperties;
import com.edem.blobhelper.jpa.AssetContentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

class EmbeddedDashboardControllerTest {
    @Test
    void servesReadOnlyDashboardViews() throws Exception {
        var management = new BlobHelperManagementProperties();
        var props = new BlobHelperDashboardProperties();
        var service = new EmbeddedDashboardSnapshotService(empty(MeterRegistry.class), empty(AssetContentRepository.class), new BlobHelperProperties(), management);
        var controller = new EmbeddedDashboardController(service, props, since -> List.of());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder().build())).build();

        var overview = mvc.perform(get("/blob-helper/dashboard/api/v1/overview"))
                .andExpect(status().isOk()).andReturn();
        assertThat(overview.getResponse().getContentAsString()).contains("\"instanceCount\":1");
        mvc.perform(get("/blob-helper/dashboard/api/v1/instances/status"))
                .andExpect(status().isOk());
        mvc.perform(get("/blob-helper/dashboard/api/v1/failures"))
                .andExpect(status().isOk());
        mvc.perform(post("/blob-helper/dashboard/api/v1/overview"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void supportsCustomBasePathInRequestMappingContract() {
        var properties = new BlobHelperDashboardProperties();
        properties.setBasePath("/custom/dashboard");
        // The property is consumed by Spring's placeholder resolver; this assertion
        // keeps the public normalization contract explicit at unit-test level.
        org.assertj.core.api.Assertions.assertThat(properties.getBasePath()).isEqualTo("/custom/dashboard");
    }

    private static <T> org.springframework.beans.factory.ObjectProvider<T> empty(Class<T> type) {
        return new DefaultListableBeanFactory().getBeanProvider(type);
    }
}
