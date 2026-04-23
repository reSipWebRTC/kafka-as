package com.kafkaasr.control;

import com.kafkaasr.control.auth.ControlPlaneAuthProperties;
import com.kafkaasr.control.policy.TenantPolicyStoreProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
        TenantPolicyStoreProperties.class,
        ControlPlaneAuthProperties.class
})
public class ControlPlaneApplication {

    public static void main(String[] args) {
        SpringApplication.run(ControlPlaneApplication.class, args);
    }
}
