package com.nila.storageconsole.k8s;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KubernetesConfig {

    /**
     * fabric8's default Config auto-detects in-cluster ServiceAccount creds at
     * /var/run/secrets/kubernetes.io/serviceaccount/ AND falls back to
     * ~/.kube/config when run locally. We explicitly set the default namespace
     * so all calls that omit a namespace target the homelab namespace.
     */
    @Bean
    public KubernetesClient kubernetesClient(@Value("${storage-console.k8s.namespace}") String namespace) {
        Config base = Config.autoConfigure(null);
        Config cfg = new ConfigBuilder(base)
                .withNamespace(namespace)
                .build();
        return new KubernetesClientBuilder().withConfig(cfg).build();
    }
}
