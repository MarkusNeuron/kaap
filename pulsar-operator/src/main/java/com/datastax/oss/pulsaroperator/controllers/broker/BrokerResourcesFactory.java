package com.datastax.oss.pulsaroperator.controllers.broker;

import com.datastax.oss.pulsaroperator.controllers.BaseResourcesFactory;
import com.datastax.oss.pulsaroperator.crds.GlobalSpec;
import com.datastax.oss.pulsaroperator.crds.broker.BrokerSpec;
import com.datastax.oss.pulsaroperator.crds.configs.ProbeConfig;
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
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.jbosslog.JBossLog;

@JBossLog
public class BrokerResourcesFactory extends BaseResourcesFactory<BrokerSpec> {

    public static String getComponentBaseName(GlobalSpec globalSpec) {
        return globalSpec.getComponents().getBrokerBaseName();
    }

    public static String getResourceName(GlobalSpec globalSpec) {
        return "%s-%s".formatted(globalSpec.getName(), getComponentBaseName(globalSpec));
    }

    public BrokerResourcesFactory(KubernetesClient client, String namespace,
                                  BrokerSpec spec, GlobalSpec global,
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

        final BrokerSpec.ServiceConfig serviceSpec = spec.getService();

        Map<String, String> annotations = null;
        if (serviceSpec.getAnnotations() != null) {
            annotations = serviceSpec.getAnnotations();
        }
        List<ServicePort> ports = new ArrayList<>();
        ports.add(new ServicePortBuilder()
                .withName("http")
                .withPort(8080)
                .build());
        ports.add(new ServicePortBuilder()
                .withName("pulsar")
                .withPort(6650)
                .build());
        if (isTlsEnabledOnBroker()) {
            ports.add(new ServicePortBuilder()
                    .withName("https")
                    .withPort(8443)
                    .build());
            ports.add(new ServicePortBuilder()
                    .withName("pulsarssl")
                    .withPort(6651)
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
                .withClusterIP(serviceSpec.getHeadless() ? "None" : null)
                .withType(serviceSpec.getType())
                .withSelector(getMatchLabels())
                .endSpec()
                .build();

        patchResource(service);
    }


    public void createConfigMap() {
        Map<String, String> data = new HashMap<>();
        final String zkServers = getZkServers();
        data.put("zookeeperServers", zkServers);
        data.put("configurationStoreServers", zkServers);
        data.put("clusterName", global.getName());
        // TODO: TLS config

        if (spec.getFunctionsWorkerEnabled()) {
            data.put("functionsWorkerEnabled", "true");
            data.put("PF_pulsarFunctionsCluster", global.getName());

            // Since function worker connects on localhost, we can always non-TLS ports
            // when running with the broker
            data.put("PF_pulsarServiceUrl", "pulsar://localhost:6650");
            data.put("PF_pulsarWebServiceUrl", "http://localhost:8080");
        }
        if (spec.getWebSocketServiceEnabled()) {
            data.put("webSocketServiceEnabled", "true");
        }

        data.put("allowAutoTopicCreationType", "non-partitioned");
        data.put("PULSAR_MEM",
                "-Xms2g -Xmx2g -XX:MaxDirectMemorySize=2g -Dio.netty.leakDetectionLevel=disabled -Dio.netty.recycler"
                        + ".linkCapacity=1024 -XX:+ExitOnOutOfMemoryError");
        data.put("PULSAR_GC", "-XX:+UseG1GC");
        data.put("PULSAR_LOG_LEVEL", "info");
        data.put("PULSAR_LOG_ROOT_LEVEL", "info");
        data.put("PULSAR_EXTRA_OPTS", "-Dpulsar.log.root.level=info");
        data.put("brokerDeduplicationEnabled", "false");
        data.put("exposeTopicLevelMetricsInPrometheus", "true");
        data.put("exposeConsumerLevelMetricsInPrometheus", "false");
        data.put("backlogQuotaDefaultRetentionPolicy", "producer_exception");

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


    public void createStatefulSet() {
        final StatefulSet statefulSet = generateStatefulSet();
        patchResource(statefulSet);
    }

    public StatefulSet generateStatefulSet() {
        final Integer replicas = spec.getReplicas();

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

        final String bkBaseName = global.getComponents().getBookkeeperBaseName();
        final String bkHostname = "%s-%s-%d.%s-%s.%s"
                .formatted(global.getName(), bkBaseName, 0, global.getName(), bkBaseName, namespace);
        initContainers.add(new ContainerBuilder()
                .withName("wait-bookkeeper-ready")
                .withImage(spec.getImage())
                .withImagePullPolicy(spec.getImagePullPolicy())
                .withCommand("sh", "-c")
                .withArgs("""
                        until nslookup %s; do
                            sleep 3;
                        done;
                        """.formatted(bkHostname))
                .build()
        );

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
        mainArg += "bin/apply-config-from-env.py conf/broker.conf && "
                + "bin/apply-config-from-env.py conf/client.conf && "
                + "bin/gen-yml-from-env.py conf/functions_worker.yml && ";

        mainArg += "OPTS=\"${OPTS} -Dlog4j2.formatMsgNoLookups=true\" exec bin/pulsar broker";


        List<ContainerPort> containerPorts = new ArrayList<>();
        containerPorts.add(new ContainerPortBuilder()
                .withName("http")
                .withContainerPort(8080)
                .build()
        );
        containerPorts.add(
                new ContainerPortBuilder()
                        .withName("pulsar")
                        .withContainerPort(6650)
                        .build()
        );
        if (isTlsEnabledGlobally()) {

            containerPorts.add(new ContainerPortBuilder()
                    .withName("https")
                    .withContainerPort(8843)
                    .build()
            );
            containerPorts.add(
                    new ContainerPortBuilder()
                            .withName("pulsarssl")
                            .withContainerPort(6651)
                            .build()
            );
        }


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
        final StatefulSet statefulSet = new StatefulSetBuilder()
                .withNewMetadata()
                .withName(resourceName)
                .withNamespace(namespace)
                .withLabels(labels)
                .endMetadata()
                .withNewSpec()
                .withServiceName(resourceName)
                .withReplicas(replicas)
                .withNewSelector()
                .withMatchLabels(getMatchLabels())
                .endSelector()
                .withUpdateStrategy(spec.getUpdateStrategy())
                .withPodManagementPolicy(spec.getPodManagementPolicy())
                .withNewTemplate()
                .withNewMetadata()
                .withLabels(labels)
                .withAnnotations(allAnnotations)
                .endMetadata()
                .withNewSpec()
                .withDnsConfig(global.getDnsConfig())
                .withServiceAccountName(spec.getServiceAccountName())
                .withNodeSelector(spec.getNodeSelectors())
                .withTerminationGracePeriodSeconds(spec.getGracePeriod().longValue())
                .withInitContainers(initContainers)
                .withContainers(containers)
                .withVolumes(volumes)
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
        return statefulSet;
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

