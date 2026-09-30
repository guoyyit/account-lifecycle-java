package com.github.guoyyit.accountlifecyclejava.web;

import com.github.guoyyit.accountlifecyclejava.domain.GroupPolicy;
import com.github.guoyyit.accountlifecyclejava.service.PolicyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/policies")
public class PolicyController {

    private final PolicyService policyService;

    public PolicyController(PolicyService policyService) {
        this.policyService = policyService;
    }

    public record PolicyRequest(Duration dormantThreshold,
                                Duration credentialValidity,
                                Duration gracePeriod,
                                Duration recycleBuffer) {
    }

    @GetMapping("/{groupName}")
    public GroupPolicy current(@PathVariable String groupName) {
        return policyService.currentPolicy(groupName);
    }

    @GetMapping("/{groupName}/versions")
    public List<GroupPolicy> versions(@PathVariable String groupName) {
        return policyService.versions(groupName);
    }

    @PostMapping("/{groupName}")
    public GroupPolicy update(@PathVariable String groupName,
                              @RequestBody PolicyRequest request) {
        return policyService.updatePolicy(groupName,
                request.dormantThreshold(), request.credentialValidity(),
                request.gracePeriod(), request.recycleBuffer());
    }
}
