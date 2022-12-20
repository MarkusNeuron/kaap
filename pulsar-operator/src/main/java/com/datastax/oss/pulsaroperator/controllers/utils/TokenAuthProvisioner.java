package com.datastax.oss.pulsaroperator.controllers.utils;

import com.datastax.oss.pulsaroperator.crds.configs.AuthConfig;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.SneakyThrows;
import lombok.extern.jbosslog.JBossLog;

@JBossLog
public class TokenAuthProvisioner {

    public static final Base64.Encoder ENCODER = Base64.getEncoder().withoutPadding();
    private final KubernetesClient client;
    private final String namespace;

    public TokenAuthProvisioner(KubernetesClient client, String namespace) {
        this.client = client;
        this.namespace = namespace;
    }

    @SneakyThrows
    public void generateSecretsIfAbsent(AuthConfig.TokenConfig tokenConfig) {
        final String prefix = "token";

        final String privateKeySecretName = "%s-%s".formatted(prefix, "private-key");
        final String publicKeySecretName = "%s-%s".formatted(prefix, "public-key");

        final Secret privateKeySecret = getSecret(privateKeySecretName);
        final Secret publicKeySecret = getSecret(publicKeySecretName);

        PrivateKey privateKey = null;
        if (privateKeySecret != null && publicKeySecret == null) {
            throw new IllegalStateException("""
                    Found private key secret %s, but the public key secret %s is missing.
                    Please delete the private key secret or create the public key one."""
                    .formatted(privateKeySecret, publicKeySecret));
        }
        if (publicKeySecret != null && publicKeySecret == null) {
            throw new IllegalStateException("""
                    Found public key secret %s, but the private key secret %s is missing.
                    Please delete the public key secret or create the private key one."""
                    .formatted(publicKeySecret, privateKeySecret));
        }
        if (publicKeySecret != null) {
            if (!publicKeySecret.getData().containsKey(tokenConfig.getPublicKeyFile())) {
                throw new IllegalStateException(
                        "Found public key secret %s, but it doesn't contain the key %s."
                                .formatted(publicKeySecretName, tokenConfig.getPublicKeyFile())
                );
            }
            final String privateKeyStr = privateKeySecret.getData().get(tokenConfig.getPrivateKeyFile());
            if (privateKeyStr == null) {
                throw new IllegalStateException(
                        "Found private key secret %s, but it doesn't contain the key %s."
                                .formatted(privateKeySecretName, tokenConfig.getPrivateKeyFile())
                );
            }
        } else {
            final KeyPair keyPair = genKeyPair();
            privateKey = keyPair.getPrivate();
            final String encodedPrivateKey = encodePrivateKey(privateKey);
            log.infof("saving pk %s", encodedPrivateKey);
            createSecret(client, namespace, privateKeySecretName, Map.of(
                    tokenConfig.getPrivateKeyFile(), encodedPrivateKey));

            final String encodedPublicKey = encodePublicKey(keyPair.getPublic());
            createSecret(client, namespace, publicKeySecretName, Map.of(
                    tokenConfig.getPublicKeyFile(), encodedPublicKey));
        }

        final Set<String> superUserRoles = tokenConfig.getSuperUserRoles();
        final Set<Secret> tokenSecrets = superUserRoles
                .stream()
                .map(role -> getSecret(role))
                .filter(s -> s != null)
                .collect(Collectors.toSet());
        if (tokenSecrets.size() == superUserRoles.size()) {
            return;
        }


        if (publicKeySecret != null) {
            final String privateKeyStr = privateKeySecret.getData().get(tokenConfig.getPrivateKeyFile());
            privateKey = parsePrivateKeyFromSecretValue(privateKeyStr);
        }

        Objects.requireNonNull(privateKey);
        for (String superUserRole : superUserRoles) {
            final String secretName = "%s-%s".formatted(prefix, superUserRole);
            if (getSecret(secretName) != null) {
                continue;
            }
            final String token = Jwts.builder()
                    .setSubject(superUserRole)
                    .signWith(privateKey)
                    .compact();

            final String tokenEncoded = ENCODER.encodeToString(token.getBytes(StandardCharsets.UTF_8));
            log.infof("Generating secret %s for role %s, value %s", secretName, superUserRole, tokenEncoded);
            createSecret(client, namespace, secretName,
                    Map.of("%s.jwt".formatted(superUserRole), tokenEncoded));
            log.infof("Generated secret %s for role %s", secretName, superUserRole);
        }
    }

    @SneakyThrows
    static KeyPair genKeyPair() {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        return keyGen.generateKeyPair();
    }

    @SneakyThrows
    static PrivateKey parsePrivateKeyFromSecretValue(String privateKeyStr) {
        final byte[] decoded = java.util.Base64.getDecoder().decode(privateKeyStr.getBytes(StandardCharsets.UTF_8));
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(decoded);
        final KeyFactory rsa = KeyFactory.getInstance("RSA");
        return rsa.generatePrivate(keySpec);
    }

    @SneakyThrows
    static Key parsePublicKeyFromSecretValue(String publicKey) {
        final byte[] decoded = java.util.Base64.getDecoder().decode(publicKey.getBytes(StandardCharsets.UTF_8));
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(decoded);
        final KeyFactory rsa = KeyFactory.getInstance("RSA");
        return rsa.generatePublic(keySpec);
    }

    static String encodePrivateKey(PrivateKey privateKey) {
        return ENCODER.encodeToString(privateKey.getEncoded());
    }

    static String encodePublicKey(PublicKey publicKey) {
        return ENCODER.encodeToString(publicKey.getEncoded());
    }

    private void createSecret(KubernetesClient client, String namespace, String secretName, Map<String, String> data) {
        final Secret secret = new SecretBuilder()
                .withNewMetadata()
                .withName(secretName)
                .endMetadata()
                .withData(data)
                .build();
        client.resource(secret)
                .inNamespace(namespace)
                .createOrReplace();
    }

    private Secret getSecret(String name) {
        return client.secrets().inNamespace(namespace).withName(name)
                .get();
    }
}
