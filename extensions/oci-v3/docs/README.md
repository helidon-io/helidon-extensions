# OCI SDK v3 Extension Docs

Documentation for the OCI SDK v3 extension belongs in this directory.

## Metrics

OCI metrics exports all supported meters from the Helidon meter registry. Meter tags
become OCI dimensions; the exporter does not add a `scope` dimension.
For meters without tags, the exporter uses `source=helidon` to satisfy
[OCI's minimum of one dimension per metric group](https://docs.oracle.com/en-us/iaas/tools/oci-cli/latest/oci_cli_docs/cmdref/monitoring/metric-data/post.html).

Helidon 27 no longer supports metric scopes. The legacy OCI metrics `scopes`
configuration setting is ignored, and `OciMetricsSupport.Builder.scopes(String[])`
is deprecated and has no effect. Use ordinary metric tags to attach categories to
exported metrics.

Metric descriptions populate OCI description metadata when enabled. Measurement
values use the declared base unit for conversion to bytes or seconds; timer
durations are exported in seconds. Timer and histogram observation counts are
exported without unit conversion.
