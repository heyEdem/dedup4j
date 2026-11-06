package com.edem.dedup4j.dashboard.api;

import com.edem.dedup4j.dashboard.persistence.InstanceRepository;
import com.edem.dedup4j.dashboard.polling.ManagementClient;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/**
 * Reads a registered instance's content rows live from its management API; nothing is stored. Content belongs to the
 * instance's dataset, so any replica answers for it. Only the registered URL is ever requested.
 */
@RestController
@RequestMapping("/api/v1/instances/{id}/contents")
public class ContentProxyController {
    private static final Set<String> SORTS = Set.of("references", "size");
    private final InstanceRepository instances;
    private final ManagementClient management = new ManagementClient();

    public ContentProxyController(InstanceRepository instances) { this.instances = instances; }

    /** {@code 501} when the instance predates content listing. */
    @GetMapping
    public JsonNode contents(@PathVariable("id") UUID id,
                             @RequestParam(name = "sort", required = false) String sort,
                             @RequestParam(name = "page", required = false) Integer page,
                             @RequestParam(name = "size", required = false) Integer size) {
        if (sort != null && !SORTS.contains(sort)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sort must be references or size");
        var query = new StringJoiner("&", "?", "").setEmptyValue("");
        if (sort != null) query.add("sort=" + sort);
        if (page != null) query.add("page=" + page);
        if (size != null) query.add("size=" + size);
        return forward(id, "/contents" + query, HttpStatus.NOT_IMPLEMENTED);
    }

    @GetMapping("/{contentId}")
    public JsonNode content(@PathVariable("id") UUID id, @PathVariable("contentId") UUID contentId) {
        return forward(id, "/contents/" + contentId, HttpStatus.NOT_FOUND);
    }

    private JsonNode forward(UUID id, String path, HttpStatus whenMissing) {
        var instance = instances.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown instance"));
        ManagementClient.Response response;
        try {
            response = management.fetch(instance.url().replaceAll("/$", "") + path);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "instance unreachable");
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "instance unreachable");
        }
        if (response.body() != null) return response.body();
        throw switch (response.status()) {
            case 400 -> new ResponseStatusException(HttpStatus.BAD_REQUEST);
            case 404 -> new ResponseStatusException(whenMissing);
            default -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, "instance answered HTTP " + response.status());
        };
    }
}
