package com.datastax.oss.pulsaroperator.controllers.autoscaler;

import com.datastax.oss.pulsaroperator.crds.CRDConstants;
import com.datastax.oss.pulsaroperator.crds.broker.Broker;
import com.datastax.oss.pulsaroperator.crds.broker.BrokerSpec;
import com.datastax.oss.pulsaroperator.crds.cluster.PulsarClusterSpec;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetrics;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetricsList;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.jbosslog.JBossLog;
import org.apache.commons.lang3.exception.ExceptionUtils;

@JBossLog
public class BrokerAutoscaler implements Runnable {

    private final KubernetesClient client;
    private final String namespace;
    private final PulsarClusterSpec clusterSpec;

    public BrokerAutoscaler(KubernetesClient client, String namespace,
                            PulsarClusterSpec clusterSpec) {
        this.client = client;
        this.namespace = namespace;
        this.clusterSpec = clusterSpec;
    }

    @Override
    public void run() {
        try {
            log.infof("Broker autoscaler starting");
            final AutoscalerSpec autoscalerSpec = clusterSpec.getAutoscaler();

            final String clusterName = clusterSpec.getGlobal().getName();
            final String brokerBaseName = clusterSpec.getGlobal()
                    .getComponents().getBrokerBaseName();
            final String brokerName = "%s-%s".formatted(clusterName, brokerBaseName);

            final Broker brokerCr = client.resources(Broker.class)
                    .inNamespace(namespace)
                    .withName(brokerName)
                    .get();
            final Integer currentExpectedReplicas = brokerCr.getSpec().getBroker().getReplicas();


            if (!isBrokerReadyToScale(clusterName, brokerBaseName, brokerName, currentExpectedReplicas)) {
                return;
            }

            final PodMetricsList metrics =
                    client.top()
                            .pods()
                            .withLabels(Map.of(CRDConstants.LABEL_CLUSTER, clusterName,
                                    CRDConstants.LABEL_COMPONENT, brokerBaseName))
                            .inNamespace(namespace)
                            .metrics();

            log.infof("Got %d broker pod metrics", metrics.getItems().size());

            Boolean scaleUpOrDown = null;

            float cpuLowerThreshold = autoscalerSpec.getBroker().getLowerCpuThreshold();
            float cpuHigherThreshold = autoscalerSpec.getBroker().getHigherCpuThreshold();

            class BrokerStat {
                float usedCpu;
                float requestedCpu;
            }
            List<BrokerStat> brokerStats = new ArrayList<>();

            for (PodMetrics item : metrics.getItems()) {
                final String podName = item.getMetadata().getName();

                float cpuUsage;
                float requestedCpu;

                Quantity cpuUsageQuantity = item.getContainers().get(0)
                        .getUsage().get("cpu");

                if (cpuUsageQuantity == null) {
                    log.infof("Broker pod %s didn't exposed CPU usage", podName);
                    continue;
                } else {
                    cpuUsage = quantityToBytes(cpuUsageQuantity);
                }

                final Quantity requestedCpuQuantity = client.pods()
                        .inNamespace(namespace)
                        .withName(podName)
                        .get().getSpec()
                        .getContainers()
                        .get(0)
                        .getResources()
                        .getRequests()
                        .get("cpu");
                if (requestedCpuQuantity == null) {
                    log.infof("Broker pod %s CPU requests not set", podName);
                    continue;
                } else {
                    requestedCpu = quantityToBytes(requestedCpuQuantity);

                }
                float percentage = cpuUsage / requestedCpu;

                log.infof("Broker pod %s CPU used/requested: %f/%f, percentage %f",
                        podName,
                        new BigDecimal(cpuUsage).setScale(2, RoundingMode.HALF_EVEN),
                        new BigDecimal(requestedCpu).setScale(2, RoundingMode.HALF_EVEN),
                        new BigDecimal(percentage).setScale(2, RoundingMode.HALF_EVEN));

                final BrokerStat stat = new BrokerStat();
                stat.requestedCpu = requestedCpu;
                stat.usedCpu = cpuUsage;
                brokerStats.add(stat);
            }

            for (BrokerStat brokerStat : brokerStats) {
                float percentage = brokerStat.usedCpu / brokerStat.requestedCpu;

                if (percentage < cpuLowerThreshold) {
                    if (scaleUpOrDown != null && scaleUpOrDown) {
                        scaleUpOrDown = null;
                        break;
                    }
                    scaleUpOrDown = false;
                } else if (percentage > cpuHigherThreshold) {
                    if (scaleUpOrDown != null && !scaleUpOrDown) {
                        scaleUpOrDown = null;
                        break;
                    }
                    scaleUpOrDown = true;
                } else {
                    scaleUpOrDown = null;
                    break;
                }
            }


            if (scaleUpOrDown != null) {
                int scaleTo = scaleUpOrDown ?
                        currentExpectedReplicas + autoscalerSpec.getBroker().getScaleUpBy()
                        : currentExpectedReplicas - autoscalerSpec.getBroker().getScaleDownBy();

                final Integer min = autoscalerSpec.getBroker().getMin();
                if (scaleTo <= 0 || (min != null && scaleTo < min)) {
                    log.infof("Can't scale down, "
                                    + "replicas is already the min. Current %d, min %d, scaleDownBy %d",
                            currentExpectedReplicas,
                            min,
                            autoscalerSpec.getBroker().getScaleDownBy()
                    );
                    return;
                }
                final Integer max = autoscalerSpec.getBroker().getMax();
                if (max != null && scaleTo > max) {
                    log.infof("Can't scale down, "
                                    + "replicas is already the max. Current %d, max %d, scaleUpBy %d",
                            currentExpectedReplicas,
                            max,
                            autoscalerSpec.getBroker().getScaleUpBy()
                    );
                    return;
                }

                final BrokerSpec broker = brokerCr.getSpec().getBroker();
                broker.setReplicas(scaleTo);
                brokerCr.getSpec().setBroker(broker);
                client.resources(Broker.class)
                        .inNamespace(namespace)
                        .withName(clusterName + "-" + brokerBaseName)
                        .patch(brokerCr);
                log.infof("Scaled brokers from %d to %d", currentExpectedReplicas, scaleTo);
            } else {
                log.infof("System is stable, no scaling needed");
            }
        } catch (Throwable tt) {
            if (ExceptionUtils.indexOfThrowable(tt, RejectedExecutionException.class) >= 0) {
                return;
            }
            log.error("Broker autoscaler error", tt);
        }
    }

    private float quantityToBytes(Quantity quantity) {
        return Quantity.getAmountInBytes(quantity)
                .setScale(2, RoundingMode.HALF_EVEN)
                .floatValue();
    }

    private boolean isBrokerReadyToScale(String clusterName, String brokerBaseName, String brokerName,
                                         Integer currentExpectedReplicas) {
        final StatefulSet statefulSet = client.apps().statefulSets()
                .inNamespace(namespace)
                .withName(brokerName)
                .get();

        final Integer readyReplicas = statefulSet.getStatus().getReadyReplicas();
        if (currentExpectedReplicas != readyReplicas) {
            log.infof("Not all sts replicas ready, expected %d, got %d", currentExpectedReplicas,
                    readyReplicas);
            return false;
        }

        final PodList allBrokerPods = client.pods()
                .inNamespace(namespace)
                .withLabels(Map.of(CRDConstants.LABEL_CLUSTER, clusterName,
                        CRDConstants.LABEL_COMPONENT, brokerBaseName))
                .list();

        if (allBrokerPods.getItems().size() != currentExpectedReplicas) {
            log.infof("Broker sts not in ready state");
            return false;
        }
        final Instant now = Instant.now();
        Instant maxStartTime = now.minusSeconds(30);
        for (Pod pod : allBrokerPods.getItems()) {
            final ContainerStatus containerStatus = pod.getStatus().getContainerStatuses().get(0);
            final Boolean ready = containerStatus.getReady();
            if (ready != null && !ready) {
                log.infof("Broker pod %s is not ready", pod.getMetadata().getName());
                return false;
            }

            final Instant podStartTime = Instant.parse(pod.getStatus().getStartTime());
            if (podStartTime.isAfter(maxStartTime)) {
                log.infof("Broker pod %s age is too little (%d seconds)", pod.getMetadata().getName(),
                        Duration.between(now, podStartTime).getSeconds());
                return false;
            }
        }
        return true;
    }
}
