package com.datastax.oss.pulsaroperator.controllers.proxy.broker;

import com.datastax.oss.pulsaroperator.controllers.BaseResourcesFactory;
import com.datastax.oss.pulsaroperator.crds.GlobalSpec;
import com.datastax.oss.pulsaroperator.crds.configs.ProbeConfig;
import com.datastax.oss.pulsaroperator.crds.proxy.ProxySpec;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.EnvFromSourceBuilder;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.Probe;
import io.fabric8.kubernetes.api.model.ProbeBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.jbosslog.JBossLog;

@JBossLog
public class ProxyResourcesFactory extends BaseResourcesFactory<ProxySpec> {

    public static String getComponentBaseName(GlobalSpec globalSpec) {
        return globalSpec.getComponents().getProxyBaseName();
    }

    public static String getResourceName(GlobalSpec globalSpec) {
        return "%s-%s".formatted(globalSpec.getName(), getComponentBaseName(globalSpec));
    }

    public ProxyResourcesFactory(KubernetesClient client, String namespace,
                                 ProxySpec spec, GlobalSpec global,
                                 OwnerReference ownerReference) {
        super(client, namespace, spec, global, ownerReference);
    }

    @Override
    protected String getComponentBaseName() {
        return getComponentBaseName(global);
    }

    @Override
    protected String getResourceName() {
        return getResourceName(global);
    }

    public void createService() {

        final ProxySpec.ServiceConfig serviceSpec = spec.getService();

        Map<String, String> annotations = null;
        if (serviceSpec.getAnnotations() != null) {
            annotations = serviceSpec.getAnnotations();
        }
        List<ServicePort> ports = new ArrayList<>();
        final boolean tlsEnabledGlobally = isTlsEnabledGlobally();
        if (tlsEnabledGlobally) {
            ports.add(new ServicePortBuilder()
                    .withName("https")
                    .withPort(8443)
                    .build());
            ports.add(new ServicePortBuilder()
                    .withName("pulsarssl")
                    .withPort(6651)
                    .build());

        }
        if (!tlsEnabledGlobally || serviceSpec.getEnablePlainTextWithTLS()) {
            ports.add(new ServicePortBuilder()
                    .withName("http")
                    .withPort(8080)
                    .build());
            ports.add(new ServicePortBuilder()
                    .withName("pulsar")
                    .withPort(6650)
                    .build());
        }
        if (serviceSpec.getAdditionalPorts() != null) {
            ports.addAll(serviceSpec.getAdditionalPorts());
        }

        final Service service = new ServiceBuilder()
                .withNewMetadata()
                .withName(resourceName)
                .withNamespace(namespace)
                .withLabels(getLabels())
                .withAnnotations(annotations)
                .endMetadata()
                .withNewSpec()
                .withPorts(ports)
                .withLoadBalancerIP(serviceSpec.getLoadBalancerIP())
                .withType(serviceSpec.getType())
                .withSelector(getMatchLabels())
                .endSpec()
                .build();

        patchResource(service);
    }


    public void createConfigMap() {
        Map<String, String> data = new HashMap<>();
        final String zkServers = getZkServers();
        data.put("brokerServiceURL", getBrokerServiceUrlPlain());
        data.put("brokerServiceURLTLS", getBrokerServiceUrlTls());
        data.put("brokerWebServiceURL", getBrokerWebServiceUrlPlain());
        data.put("brokerWebServiceURLTLS", getBrokerWebServiceUrlTls());
        data.put("zookeeperServers", zkServers);
        data.put("configurationStoreServers", zkServers);

        data.put("PULSAR_MEM", "-Xms1g -Xmx1g -XX:MaxDirectMemorySize=1g");
        data.put("PULSAR_GC", "-XX:+UseG1GC");
        data.put("PULSAR_LOG_LEVEL", "info");
        data.put("PULSAR_LOG_ROOT_LEVEL", "info");
        data.put("PULSAR_EXTRA_OPTS", "-Dpulsar.log.root.level=info");
        data.put("numHttpServerThreads", "10");

        if (spec.getConfig() != null) {
            data.putAll(spec.getConfig());
        }

        final ConfigMap configMap = new ConfigMapBuilder()
                .withNewMetadata()
                .withName(resourceName)
                .withNamespace(namespace)
                .withLabels(getLabels()).endMetadata()
                .withData(data)
                .build();
        patchResource(configMap);
    }


    public void createDeployment() {
        Map<String, String> labels = getLabels();
        Map<String, String> allAnnotations = new HashMap<>();
        allAnnotations.put("prometheus.io/scrape", "true");
        allAnnotations.put("prometheus.io/port", "8080");
        if (spec.getAnnotations() != null) {
            allAnnotations.putAll(spec.getAnnotations());
        }

        List<VolumeMount> volumeMounts = new ArrayList<>();
        List<Volume> volumes = new ArrayList<>();
        addTlsVolumesIfEnabled(volumeMounts, volumes, getTlsSecretNameForBroker());

        List<Container> initContainers = new ArrayList<>();


        initContainers.add(createWaitBKReadyContainer());

        if (spec.getInitContainer() != null) {
            volumes.add(
                    new VolumeBuilder()
                            .withName("lib-data")
                            .withNewEmptyDir().endEmptyDir()
                            .build()
            );
            volumeMounts.add(
                    new VolumeMountBuilder()
                            .withName("lib-data")
                            .withMountPath(spec.getInitContainer().getEmptyDirPath())
                            .build()
            );
            initContainers.add(new ContainerBuilder()
                    .withName("add-libs")
                    .withImage(spec.getInitContainer().getImage())
                    .withImagePullPolicy(spec.getInitContainer().getImagePullPolicy())
                    .withCommand(spec.getInitContainer().getCommand())
                    .withArgs(spec.getInitContainer().getArgs())
                    .withVolumeMounts(new VolumeMountBuilder()
                            .withName("lib-data")
                            .withMountPath(spec.getInitContainer().getEmptyDirPath())
                            .build())
                    .build());
        }

        final Probe probe = createProbe();
        String mainArg = "";
        if (isTlsEnabledGlobally()) {
            mainArg += "openssl pkcs8 -topk8 -inform PEM -outform PEM -in /pulsar/certs/tls.key "
                    + "-out /pulsar/tls-pk8.key -nocrypt && "
                    + ". /pulsar/tools/certconverter.sh && ";
        }
        mainArg += "bin/apply-config-from-env.py conf/proxy.conf && ";
        mainArg += "OPTS=\"${OPTS} -Dlog4j2.formatMsgNoLookups=true\" exec bin/pulsar proxy";


        List<ContainerPort> containerPorts = new ArrayList<>();
        containerPorts.add(new ContainerPortBuilder()
                .withName("wss")
                .withContainerPort(8001)
                .build()
        );
        List<Container> containers = List.of(
                new ContainerBuilder()
                        .withName(resourceName)
                        .withImage(spec.getImage())
                        .withImagePullPolicy(spec.getImagePullPolicy())
                        .withLivenessProbe(probe)
                        .withReadinessProbe(probe)
                        .withResources(spec.getResources())
                        .withCommand("sh", "-c")
                        .withArgs(mainArg)
                        .withPorts(containerPorts)
                        .withEnvFrom(new EnvFromSourceBuilder()
                                .withNewConfigMapRef()
                                .withName(resourceName)
                                .endConfigMapRef()
                                .build())
                        .withVolumeMounts(volumeMounts)
                        .build()
        );

        Deployment deployment = new DeploymentBuilder()
                .withNewMetadata()
                .withName(resourceName)
                .withNamespace(namespace)
                .withLabels(labels)
                .endMetadata()
                .withNewSpec()
                .withReplicas(spec.getReplicas())
                .withNewSelector()
                .withMatchLabels(getMatchLabels())
                .endSelector()
                .withStrategy(spec.getUpdateStrategy())
                .withNewTemplate()
                .withNewMetadata()
                .withLabels(labels)
                .withAnnotations(allAnnotations)
                .endMetadata()
                .withNewSpec()
                .withDnsConfig(global.getDnsConfig())
                .withNodeSelector(spec.getNodeSelectors())
                .withTerminationGracePeriodSeconds(spec.getGracePeriod().longValue())
                .withInitContainers(initContainers)
                .withContainers(containers)
                .withVolumes(volumes)
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
        patchResource(deployment);
    }

    private Probe createProbe() {
        final ProbeConfig specProbe = spec.getProbe();
        if (specProbe == null) {
            return null;
        }
        return new ProbeBuilder()
                .withNewExec()
                .withCommand("sh", "-c", "curl -s --max-time %d --fail http://localhost:8080/metrics/ > /dev/null"
                        .formatted(specProbe.getTimeout()))
                .endExec()
                .withInitialDelaySeconds(specProbe.getInitial())
                .withPeriodSeconds(specProbe.getPeriod())
                .withTimeoutSeconds(specProbe.getTimeout())
                .build();
    }

    public void createPodDisruptionBudgetIfEnabled() {
        createPodDisruptionBudgetIfEnabled(spec.getPdb());
    }

}

