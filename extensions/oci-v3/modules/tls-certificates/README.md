# Helidon OCI Certificates TLS Manager

The `OciCertificatesTlsManager` loads a TLS certificate and trust anchor from
[OCI Certificates](https://docs.oracle.com/en-us/iaas/Content/certificates/home.htm) and periodically checks for a newer
current certificate version. It supports two private-key sources:

- `vault` (the default) preserves the original behavior: the public certificate bundle is loaded from OCI Certificates
  and a separately configured software-protected key is exported through OCI Key Management Service.
- `certificate-bundle` loads the leaf certificate, chain, and matching private key from one OCI-managed certificate
  bundle. OCI Certificates can renew such a certificate automatically, and the manager adopts the newly current
  certificate/key pair without restarting the application.

Add the module to the application:

```xml
<dependency>
    <groupId>io.helidon.extensions.oci.v3</groupId>
    <artifactId>helidon-extensions-oci-v3-tls-certificates</artifactId>
</dependency>
```

## OCI-managed certificate bundle

Use an OCI-issued certificate whose private key is stored by OCI Certificates. An imported or externally managed
certificate that exposes only a public bundle cannot be used in this mode.

```yaml
server:
  sockets:
    - name: secured
      port: 8443
      tls:
        manager:
          oci-certificates-tls-manager:
            schedule: "0/30 * * * * ? *"
            private-key-source: certificate-bundle
            ca-ocid: ${CA_OCID}
            cert-ocid: ${SERVER_CERT_OCID}
```

The manager requests the `CURRENT` bundle as `CERTIFICATE_CONTENT_WITH_PRIVATE_KEY`. It verifies that the returned
private key matches the leaf certificate before installing the identity. Both RSA and EC PKCS#8 keys are supported,
including passphrase-protected keys; an OCI-provided passphrase is used only while decoding that bundle.

By default, polling this mode does not reload TLS when both the certificate version and CA certificate are unchanged.
A newer identity version or independently rotated CA is installed as one complete TLS update. If download, parsing,
validation, or reload fails, the last successfully installed TLS material remains active and the candidate update is
retried on a later poll.

The leaf private key is materialized in application JVM memory. This mode does not provide non-exportable HSM-backed
TLS signing; the CA signing key can remain separately HSM protected.

The workload needs permission to read the private leaf bundle and the configured CA bundle. Restrict the leaf permission
to private bundle retrieval where practical, for example:

```text
Allow dynamic-group <dynamic-group> to read leaf-certificate-bundles in compartment <compartment>
  where target.leaf-certificate.bundle-type = 'CERTIFICATE_CONTENT_WITH_PRIVATE_KEY'
Allow dynamic-group <dynamic-group> to read certificate-authority-bundles in compartment <compartment>
```

See the OCI documentation for
[certificate IAM policies](https://docs.oracle.com/en-us/iaas/Content/Identity/policyreference/certificatespolicyreference.htm),
[viewing certificate bundles](https://docs.oracle.com/en-us/iaas/Content/certificates/viewing-certificate-version-bundle.htm),
and [automatic renewal](https://docs.oracle.com/en-us/iaas/Content/certificates/renewing-certificate.htm).

## Vault-exported private key

Existing configurations remain on this mode when `private-key-source` is absent. The certificate and configured Vault
key must represent the same TLS identity, and the Vault leaf key must be software protected and exportable.

```yaml
server:
  sockets:
    - name: secured
      port: 8443
      tls:
        manager:
          oci-certificates-tls-manager:
            schedule: "0/30 * * * * ? *"
            private-key-source: vault
            vault-crypto-endpoint: ${VAULT_CRYPTO_ENDPOINT}
            ca-ocid: ${CA_OCID}
            cert-ocid: ${SERVER_CERT_OCID}
            key-ocid: ${SERVER_KEY_OCID}
            key-password: ${SERVER_KEY_PASSWORD}
```

Do not combine `certificate-bundle` with `key-ocid`, `key-password`, `vault-crypto-endpoint`, or
`vault-management-endpoint`; mixed configuration is rejected rather than silently choosing one key source.

## Reload policy

`always-reload` controls whether TLS rebuilding continues when the downloaded identity and CA material are unchanged.
Every scheduled poll downloads both the identity bundle and independently versioned CA bundle so either kind of rotation
can be detected. When the option is absent, its effective default depends on the private-key source:

- `vault`: `true`, preserving the original behavior for existing configurations;
- `certificate-bundle`: `false`, avoiding a TLS reload until OCI publishes a new current identity or CA certificate.

Set `always-reload: false` to opt Vault mode into version-gated reloads, or `always-reload: true` to force managed bundle
reloads on every poll.
