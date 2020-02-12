/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBinding;
import io.strimzi.api.kafka.model.KafkaUser;
import io.strimzi.api.kafka.model.status.Condition;
import io.strimzi.systemtest.cli.KafkaCmdClient;
import io.strimzi.systemtest.resources.KubernetesResource;
import io.strimzi.systemtest.resources.ResourceManager;
import io.strimzi.systemtest.resources.crd.KafkaClientsResource;
import io.strimzi.systemtest.resources.crd.KafkaConnectResource;
import io.strimzi.systemtest.resources.crd.KafkaConnectS2IResource;
import io.strimzi.systemtest.resources.crd.KafkaResource;
import io.strimzi.systemtest.resources.crd.KafkaUserResource;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaUserUtils;
import io.strimzi.systemtest.utils.kubeUtils.objects.SecretUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static io.strimzi.systemtest.Constants.ACCEPTANCE;
import static io.strimzi.systemtest.Constants.REGRESSION;
import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

@Tag(REGRESSION)
class AllNamespaceST extends AbstractNamespaceST {

    private static final Logger LOGGER = LogManager.getLogger(AllNamespaceST.class);
    private static final String THIRD_NAMESPACE = "third-namespace-test";
    private static final String SECOND_CLUSTER_NAME = CLUSTER_NAME + "-second";

    /**
     * Test the case where the TO is configured to watch a different namespace that it is deployed in
     */
    @Test
    void testTopicOperatorWatchingOtherNamespace() {
        LOGGER.info("Deploying TO to watch a different namespace that it is deployed in");
        String previousNamespace = cluster.setNamespace(THIRD_NAMESPACE);
        List<String> topics = KafkaCmdClient.listTopicsUsingPodCli(CLUSTER_NAME, 0);
        assertThat(topics, not(hasItems(TOPIC_NAME)));

        deployNewTopic(SECOND_NAMESPACE, THIRD_NAMESPACE, TOPIC_NAME);
        deleteNewTopic(SECOND_NAMESPACE, TOPIC_NAME);
        cluster.setNamespace(previousNamespace);
    }

    /**
     * Test the case when Kafka will be deployed in different namespace than CO
     */
    @Test
    @Tag(ACCEPTANCE)
    void testKafkaInDifferentNsThanClusterOperator() {
        LOGGER.info("Deploying Kafka cluster in different namespace than CO when CO watches all namespaces");
        checkKafkaInDiffNamespaceThanCO(SECOND_CLUSTER_NAME, SECOND_NAMESPACE);
    }

    /**
     * Test the case when MirrorMaker will be deployed in different namespace than CO when CO watches all namespaces
     */
    @Test
    void testDeployMirrorMakerAcrossMultipleNamespace() {
        LOGGER.info("Deploying Kafka MirrorMaker in different namespace than CO when CO watches all namespaces");
        checkMirrorMakerForKafkaInDifNamespaceThanCO(SECOND_CLUSTER_NAME);
    }

    @Test
    void testDeployKafkaConnectAndKafkaConnectorInOtherNamespaceThanCO() throws Exception {
        String topicName = "test-topic-" + new Random().nextInt(Integer.MAX_VALUE);
        String previousNamespace = cluster.setNamespace(SECOND_NAMESPACE);
        // Deploy Kafka Connect in other namespace than CO
        KafkaConnectResource.kafkaConnect(SECOND_CLUSTER_NAME, 1)
            .editMetadata()
                .addToAnnotations("strimzi.io/use-connector-resources", "true")
            .endMetadata().done();
        // Deploy Kafka Connector
        deployKafkaConnectorWithSink(SECOND_CLUSTER_NAME, SECOND_NAMESPACE, topicName, "kafka-connect");

        cluster.setNamespace(previousNamespace);
    }

    @Test
    void testDeployKafkaConnectS2IAndKafkaConnectorInOtherNamespaceThanCO() throws Exception {
        String topicName = "test-topic-" + new Random().nextInt(Integer.MAX_VALUE);
        String previousNamespace = cluster.setNamespace(SECOND_NAMESPACE);
        // Deploy Kafka Connect in other namespace than CO
        KafkaConnectS2IResource.kafkaConnectS2I(SECOND_CLUSTER_NAME, SECOND_CLUSTER_NAME, 1)
            .editMetadata()
                .addToAnnotations("strimzi.io/use-connector-resources", "true")
            .endMetadata().done();
        // Deploy Kafka Connector
        deployKafkaConnectorWithSink(SECOND_CLUSTER_NAME, SECOND_NAMESPACE, topicName, "kafka-connect-s2i");

        cluster.setNamespace(previousNamespace);
    }

    @Test
    void testUOWatchingOtherNamespace() {
        String previousNamespace = cluster.setNamespace(SECOND_NAMESPACE);
        LOGGER.info("Creating user in other namespace than CO and Kafka cluster with UO");
        KafkaUserResource.tlsUser(CLUSTER_NAME, USER_NAME).done();

        // Check that UO created a secret for new user
        SecretUtils.waitForSecretReady(USER_NAME);
        cluster.setNamespace(previousNamespace);
    }

    @Test
    void testUserInDifferentNamespace() throws Exception {
        String startingNamespace = cluster.setNamespace(SECOND_NAMESPACE);
        KafkaUser user = KafkaUserResource.tlsUser(CLUSTER_NAME, USER_NAME).done();

        SecretUtils.waitForSecretReady(USER_NAME);
        KafkaUserUtils.waitForKafkaUserCreation(USER_NAME);
        Condition kafkaCondition = KafkaUserResource.kafkaUserClient().inNamespace(SECOND_NAMESPACE).withName(USER_NAME)
                .get().getStatus().getConditions().get(0);
        LOGGER.info("Kafka User condition status: {}", kafkaCondition.getStatus());
        LOGGER.info("Kafka User condition type: {}", kafkaCondition.getType());

        assertThat(kafkaCondition.getType(), is("Ready"));

        List<Secret> secretsOfSecondNamespace = kubeClient(SECOND_NAMESPACE).listSecrets();

        cluster.setNamespace(THIRD_NAMESPACE);

        for (Secret s : secretsOfSecondNamespace) {
            if (s.getMetadata().getName().equals(USER_NAME)) {
                LOGGER.info("Copying secret {} from namespace {} to namespace {}", s, SECOND_NAMESPACE, THIRD_NAMESPACE);
                copySecret(s, THIRD_NAMESPACE, USER_NAME);
            }
        }

        KafkaClientsResource.deployKafkaClients(true, CLUSTER_NAME + "-" + Constants.KAFKA_CLIENTS, user).done();

        final String defaultKafkaClientsPodName =
                ResourceManager.kubeClient().listPodsByPrefixInName(CLUSTER_NAME + "-" + Constants.KAFKA_CLIENTS).get(0).getMetadata().getName();

        internalKafkaClient.setPodName(defaultKafkaClientsPodName);

        LOGGER.info("Checking produceed and consumed messages to pod:{}", internalKafkaClient.getPodName());
        internalKafkaClient.checkProducedAndConsumedMessages(
                internalKafkaClient.sendMessagesTls(TOPIC_NAME, THIRD_NAMESPACE, CLUSTER_NAME, USER_NAME, 50, "TLS"),
                internalKafkaClient.receiveMessagesTls(TOPIC_NAME, THIRD_NAMESPACE, CLUSTER_NAME, USER_NAME, 50, "TLS", CONSUMER_GROUP_NAME)
        );

        cluster.setNamespace(startingNamespace);
    }

    void copySecret(Secret sourceSecret, String targetNamespace, String targetName) {
        Secret s = new SecretBuilder(sourceSecret)
                    .withNewMetadata()
                        .withName(targetName)
                        .withNamespace(targetNamespace)
                    .endMetadata()
                    .build();
        kubeClient(targetNamespace).getClient().secrets().inNamespace(targetNamespace).createOrReplace(s);
    }

    private void deployTestSpecificResources() {
        LOGGER.info("Creating resources before the test class");
        prepareEnvForOperator(CO_NAMESPACE, Arrays.asList(CO_NAMESPACE, SECOND_NAMESPACE, THIRD_NAMESPACE));

        // Apply role bindings in CO namespace
        applyRoleBindings(CO_NAMESPACE);

        // Create ClusterRoleBindings that grant cluster-wide access to all OpenShift projects
        List<ClusterRoleBinding> clusterRoleBindingList = KubernetesResource.clusterRoleBindingsForAllNamespaces(CO_NAMESPACE);
        clusterRoleBindingList.forEach(clusterRoleBinding ->
                KubernetesResource.clusterRoleBinding(clusterRoleBinding, CO_NAMESPACE));
        // 050-Deployment
        KubernetesResource.clusterOperator("*").done();

        String previousNamespace = cluster.setNamespace(THIRD_NAMESPACE);

        KafkaResource.kafkaEphemeral(CLUSTER_NAME, 1, 1)
            .editSpec()
                .editEntityOperator()
                    .editTopicOperator()
                        .withWatchedNamespace(SECOND_NAMESPACE)
                    .endTopicOperator()
                    .editUserOperator()
                        .withWatchedNamespace(SECOND_NAMESPACE)
                    .endUserOperator()
                .endEntityOperator()
            .endSpec()
            .done();

        cluster.setNamespace(SECOND_NAMESPACE);
        // Deploy Kafka in other namespace than CO
        KafkaResource.kafkaEphemeral(SECOND_CLUSTER_NAME, 3).done();

        cluster.setNamespace(previousNamespace);
    }

    @BeforeAll
    void setupEnvironment() {
        deployTestSpecificResources();
    }

    @Override
    protected void recreateTestEnv(String coNamespace, List<String> bindingsNamespaces) {
        teardownEnvForOperator();
        ResourceManager.setClassResources();
        deployTestSpecificResources();
        ResourceManager.setMethodResources();
    }
}
