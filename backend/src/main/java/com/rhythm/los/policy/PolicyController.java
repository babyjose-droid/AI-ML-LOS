package com.rhythm.los.policy;

import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/policies")
public class PolicyController {
    private final PolicyRepository repo;
    private final PolicyService service;

    public PolicyController(PolicyRepository repo, PolicyService service) {
        this.repo = repo;
        this.service = service;
    }

    @GetMapping("/live")
    public PolicyParams live() { return service.live(); }

    @GetMapping
    public List<PolicyVersion> all() { return repo.findAllByOrderByIdDesc(); }
}
