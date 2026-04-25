package com.kafkaasr.control;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.kafkaasr.control.distribution.JdbcTenantPolicyDistributionStatusRepository;
import com.kafkaasr.control.distribution.TenantPolicyDistributionStatusRepository;
import com.kafkaasr.control.policy.JdbcTenantPolicyRepository;
import com.kafkaasr.control.policy.TenantPolicyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "control.policy-store.backend=jdbc"
})
class ControlPlaneJdbcPolicyStoreWiringTests {

    @Autowired
    private TenantPolicyRepository tenantPolicyRepository;

    @Autowired
    private TenantPolicyDistributionStatusRepository distributionStatusRepository;

    @Test
    void contextLoadsJdbcPolicyStoreBackend() {
        assertInstanceOf(JdbcTenantPolicyRepository.class, tenantPolicyRepository);
        assertInstanceOf(JdbcTenantPolicyDistributionStatusRepository.class, distributionStatusRepository);
    }
}
