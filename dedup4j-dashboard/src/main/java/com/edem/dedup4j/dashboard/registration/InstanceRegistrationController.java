package com.edem.dedup4j.dashboard.registration;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import com.edem.dedup4j.dashboard.persistence.InstanceRepository;

@RestController
@RequestMapping("/api/v1/instances")
public class InstanceRegistrationController {

    private final ConcurrentHashMap<java.util.UUID, InstanceRegistration> registrations = new ConcurrentHashMap<>();
    private final InstanceRepository repository;

    public InstanceRegistrationController() { this.repository = null; }

    @Autowired
    public InstanceRegistrationController(InstanceRepository repository) { this.repository = repository; }

    @PostMapping
    public InstanceRegistration register(@RequestBody InstanceRegistration registration) {
        InstanceRegistration updated = new InstanceRegistration(
                registration.instanceId(),
                registration.instanceName(),
                registration.advertisedUrl(),
                Instant.now());
        if (repository == null) registrations.put(updated.instanceId(), updated);
        else repository.save(updated.instanceId(), updated.instanceName(), updated.advertisedUrl(), updated.registeredAt());
        return updated;
    }

    @GetMapping
    public List<InstanceRegistration> instances() {
        if (repository != null) return repository.findAll().stream().map(i -> new InstanceRegistration(i.id(), i.name(), i.url(), i.registeredAt())).toList();
        return registrations.values().stream()
                .sorted(Comparator.comparing(InstanceRegistration::instanceName))
                .toList();
    }
}
